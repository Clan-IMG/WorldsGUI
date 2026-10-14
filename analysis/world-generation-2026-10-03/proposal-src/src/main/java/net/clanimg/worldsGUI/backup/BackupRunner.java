package net.clanimg.worldsGUI.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import net.clanimg.worldsGUI.backup.RestoreStateStore.Phase;
import net.clanimg.worldsGUI.backup.RestoreStateStore.State;
import net.clanimg.worldsGUI.data.WorldsRepository;
import net.clanimg.worldsGUI.data.WorldsRepository.BackupDownload;
import net.clanimg.worldsGUI.data.WorldsRepository.BackupJob;
import net.clanimg.worldsGUI.data.WorldsRepository.BackupStartResult;
import org.bukkit.Bukkit;

/**
 * Führt Backup- und Restore-Aufträge aus. Läuft auf Worker-Threads; alles, was die Bukkit-Welt oder
 * Spieler anfasst, geht über {@link BackupWorldHooks} auf den Hauptthread.
 *
 * <p>Backup: Welt speichern, Fingerabdruck bilden (unverändert = überspringen), dann als Zip-Datenstrom
 * in Teilen zu R2 hochladen.
 *
 * <p>Restore (Welt bleibt bis kurz vor dem Tausch spielbar): Backup entpacken und prüfen, Welt entladen,
 * Sicherheits-Backup (Undo) der aktuellen Welt hochladen, Ordner tauschen, Welt laden. Jede Phase ist
 * in {@link RestoreStateStore} vermerkt, damit ein Neustart mitten im Restore sauber weitermachen kann.
 */
final class BackupRunner {
    private static final long MIB = 1024L * 1024L;
    private static final long MAX_PART_SIZE_BYTES = 1024L * MIB;
    private static final int TARGET_MAX_PARTS = 9_000;
    private static final long ZIP_OVERHEAD_PER_FILE_BYTES = 512L;
    private static final int DOWNLOAD_ATTEMPTS = 3;
    private static final int COMPLETE_ATTEMPTS = 3;
    private static final int UNLOAD_ATTEMPTS = 10;
    private static final Duration DOWNLOAD_RESPONSE_TIMEOUT = Duration.ofMinutes(2);

    /** Führt eine Aktion auf dem Hauptthread aus und wartet auf das Ergebnis. */
    interface MainThread {
        <T> T call(Supplier<T> action);
    }

    record UploadOutcome(boolean skipped, long sizeBytes) {
    }

    private final Logger logger;
    private final Path worldContainer;
    private final WorldsRepository repository;
    private final Supplier<BackupSettings> settings;
    private final BackupWorldHooks hooks;
    private final MainThread mainThread;
    private final HttpClient httpClient;
    private final RestoreStateStore states;
    private final Set<String> busyWorlds;

    BackupRunner(
        Logger logger,
        WorldsRepository repository,
        Supplier<BackupSettings> settings,
        BackupWorldHooks hooks,
        MainThread mainThread,
        HttpClient httpClient,
        RestoreStateStore states,
        Set<String> busyWorlds
    ) {
        this.logger = logger;
        // Paper liefert den Weltordner relativ ("./"); absolut+normalisiert, damit Pfadvergleiche stimmen.
        this.worldContainer = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        this.repository = repository;
        this.settings = settings;
        this.hooks = hooks;
        this.mainThread = mainThread;
        this.httpClient = httpClient;
        this.states = states;
        this.busyWorlds = busyWorlds;
    }

    // ------------------------------------------------------------------
    // Backup
    // ------------------------------------------------------------------

    /** @return Ergebnistext für den Auftrag */
    String runBackup(JobContext ctx) {
        BackupJob job = ctx.job();
        UploadOutcome outcome = uploadWorld(ctx, job.worldName(), job.kind());
        return outcome.skipped() ? "Unverändert, kein neues Backup nötig" : "Backup erstellt (" + BackupFormat.size(outcome.sizeBytes()) + ")";
    }

    private UploadOutcome uploadWorld(JobContext ctx, String worldName, String kind) {
        Path folder = worldFolder(worldName);
        if (!Files.isDirectory(folder)) {
            throw new BackupException("Der Weltordner wurde auf diesem Server nicht gefunden");
        }

        ctx.phase("prepare");
        Boolean previousAutoSave = mainThread.call(() -> hooks.pauseAutoSave(worldName));
        try {
            ctx.checkCancelled();
            requireLevelDat(folder);
            WorldFingerprint fingerprint = fingerprintOf(folder);
            long partSize = choosePartSize(fingerprint);

            BackupStartResult start = repository.startBackupUpload(
                worldName,
                kind,
                hooks.localServerName(),
                partSize,
                fingerprint.fingerprint()
            );
            if (!start.ok()) {
                throw new BackupException(describeStartError(start.error()));
            }
            if (start.skipped()) {
                return new UploadOutcome(true, 0L);
            }

            ctx.phase("upload");
            return zipAndUpload(ctx, folder, start.backupId(), (int) partSize);
        } finally {
            if (previousAutoSave != null) {
                try {
                    runOnMainThread(() -> hooks.resumeAutoSave(worldName, previousAutoSave));
                } catch (RuntimeException ex) {
                    logger.warning("Autosave für Welt " + worldName + " konnte nicht wieder aktiviert werden: " + ex.getMessage());
                }
            }
        }
    }

    private WorldFingerprint fingerprintOf(Path folder) {
        try {
            return WorldFingerprint.compute(folder);
        } catch (IOException ex) {
            throw new BackupException("Weltordner konnte nicht gelesen werden: " + ex.getMessage(), ex);
        }
    }

    private long choosePartSize(WorldFingerprint fingerprint) {
        // Das Zip ist höchstens so groß wie die Rohdaten (plus etwas Overhead pro Datei).
        long upperBound = fingerprint.totalBytes() + fingerprint.fileCount() * ZIP_OVERHEAD_PER_FILE_BYTES;
        long needed = (upperBound + TARGET_MAX_PARTS - 1) / TARGET_MAX_PARTS;
        long minimum = settings.get().minPartSizeMb() * MIB;
        long partSize = Math.max(minimum, ((needed + MIB - 1) / MIB) * MIB);
        if (partSize > MAX_PART_SIZE_BYTES) {
            throw new BackupException("Die Welt ist zu groß für ein Backup");
        }
        return partSize;
    }

    private UploadOutcome zipAndUpload(JobContext ctx, Path folder, long backupId, int partSize) {
        PartUploadOutputStream upload = new PartUploadOutputStream(
            repository,
            httpClient,
            ctx,
            backupId,
            partSize,
            settings.get().partUploadRetries()
        );

        try {
            try (ZipOutputStream zip = new ZipOutputStream(upload)) {
                zip.setLevel(settings.get().compressionLevel());
                writeEntries(ctx, folder, zip);
            }

            long size = upload.totalBytes();
            completeUpload(ctx, backupId, upload, size);
            return new UploadOutcome(false, size);
        } catch (BackupCancelledException ex) {
            abortQuietly(backupId);
            throw ex;
        } catch (IOException | RuntimeException ex) {
            abortQuietly(backupId);
            if (ex instanceof BackupException backupException) {
                throw backupException;
            }
            throw new BackupException("Backup fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    private void writeEntries(JobContext ctx, Path folder, ZipOutputStream zip) throws IOException {
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                ctx.checkCancelled();
                String relative = folder.relativize(file).toString().replace('\\', '/');
                if (WorldFingerprint.isExcludedFromArchive(relative)) {
                    return FileVisitResult.CONTINUE;
                }

                // A successfully uploaded archive must not silently omit world files.
                try (InputStream in = Files.newInputStream(file)) {
                    ZipEntry entry = new ZipEntry(relative);
                    entry.setTime(attrs.lastModifiedTime().toMillis());
                    zip.putNextEntry(entry);
                    in.transferTo(zip);
                    zip.closeEntry();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                throw exc;
            }
        });
    }

    private void completeUpload(JobContext ctx, long backupId, PartUploadOutputStream upload, long size) {
        String sha256 = upload.sha256();
        for (int attempt = 1; attempt <= COMPLETE_ATTEMPTS; attempt++) {
            ctx.checkCancelled();
            if (repository.completeBackupUpload(backupId, upload.parts(), size, sha256)) {
                return;
            }
            if (attempt < COMPLETE_ATTEMPTS) {
                pause(3_000L);
            }
        }
        throw new BackupException("Das Backup konnte nicht abgeschlossen werden");
    }

    private void abortQuietly(long backupId) {
        try {
            repository.abortBackupUpload(backupId);
        } catch (RuntimeException ex) {
            logger.warning("Backup-Upload " + backupId + " konnte nicht abgebrochen werden: " + ex.getMessage());
        }
    }

    private String describeStartError(String detail) {
        return switch (detail == null ? "" : detail) {
            case "manual-limit" -> "Das Limit für manuelle Backups ist erreicht";
            case "world-not-found" -> "Die Welt ist nicht mehr registriert";
            case "api-unreachable" -> "Die API ist nicht erreichbar";
            default -> "Das Backup konnte nicht gestartet werden" + (detail == null || detail.isBlank() ? "" : " (" + detail + ")");
        };
    }

    // ------------------------------------------------------------------
    // Restore
    // ------------------------------------------------------------------

    /** @return Ergebnistext für den Auftrag */
    String runRestore(JobContext ctx) {
        String world = ctx.job().worldName();
        if (!busyWorlds.add(world)) {
            throw new BackupException("Für diese Welt läuft bereits ein Restore");
        }
        try {
            return runRestoreLocked(ctx);
        } finally {
            busyWorlds.remove(world);
        }
    }

    private String runRestoreLocked(JobContext ctx) {
        BackupJob job = ctx.job();
        String world = job.worldName();
        Path folder = worldFolder(world);
        Path tmp = tempFolder(world);
        Path old = previousFolder(world);

        Optional<State> state = states.read(world);
        if (state.isPresent() && state.get().jobId() == job.id() && state.get().phase() == Phase.DONE) {
            // Der Tausch ist in einem früheren Versuch schon passiert (z.B. Neustart kurz vor Ende).
            return finishRestore(ctx, world);
        }

        // Reste eines früheren, unterbrochenen Restores in einen konsistenten Zustand bringen.
        recoverFolders(world);
        states.clear(world);
        if (Files.exists(old)) {
            throw new BackupException("Vorheriger Weltstand bleibt erhalten: " + old
                + ". Vor einem weiteren Restore offline archivieren.");
        }
        deleteRecursive(tmp);

        ctx.phase("download");
        BackupDownload download = repository.requestBackupDownload(job.backupId())
            .orElseThrow(() -> new BackupException("Das Backup existiert nicht mehr"));

        try {
            writeState(world, job.id(), Phase.PREPARING);
            downloadAndExtract(ctx, download, tmp);
        } catch (RuntimeException ex) {
            deleteRecursive(tmp);
            states.clear(world);
            throw ex;
        }

        try {
            if (Files.isDirectory(folder)) {
                ctx.phase("stop-world");
                if (!unloadWithRetries(ctx, world)) {
                    throw new BackupException("Die Welt konnte nicht entladen werden");
                }

                ctx.phase("undo-backup");
                uploadWorld(ctx, world, "undo");
            }

            ctx.phase("swap");
            ctx.checkCancelled();
            if (mainThread.call(() -> Bukkit.getWorld(world) != null)) {
                throw new BackupException("Welt wurde während des Restores erneut geladen; kein Ordner-Tausch");
            }
            requireLevelDat(tmp);
            writeState(world, job.id(), Phase.SWAPPING);
            if (Files.exists(folder)) {
                moveDirectoryOrFail(folder, old);
            }
            moveDirectoryOrFail(tmp, folder);
            writeState(world, job.id(), Phase.DONE);

            return finishRestore(ctx, world);
        } catch (RuntimeException ex) {
            // Retain state and both world versions; no speculative load or destructive rollback.
            logger.severe("Restore abgebrochen; Status und Weltstände bleiben zur Offline-Prüfung erhalten: " + world);
            throw ex;
        }
    }

    /** Spieler werden asynchron zur Lobby verbunden, deshalb dauert es einen Moment, bis die Welt leer ist. */
    private boolean unloadWithRetries(JobContext ctx, String world) {
        for (int attempt = 1; attempt <= UNLOAD_ATTEMPTS; attempt++) {
            ctx.checkCancelled();
            if (mainThread.call(() -> hooks.unloadWorldForRestore(world))) {
                return true;
            }
            pause(2_000L);
        }
        return false;
    }

    private String finishRestore(JobContext ctx, String world) {
        Path folder = worldFolder(world);
        Path old = previousFolder(world);

        ctx.phase("load");
        requireLevelDat(folder);
        if (!mainThread.call(() -> {
            if (Bukkit.getWorld(world) != null) {
                throw new BackupException("Welt ist vor dem vorgesehenen Restore-Laden bereits geladen");
            }
            return hooks.loadWorld(world);
        })) {
            throw new BackupException("Welt konnte nicht geladen werden; beide Stände und Restore-Status bleiben erhalten");
        }

        runOnMainThread(() -> hooks.refreshWorldProtection(world));
        states.clear(world);
        if (Files.exists(old)) {
            logger.info("Vorheriger Weltstand bleibt erhalten: " + old);
        }
        logger.info("Welt " + world + " aus Backup wiederhergestellt (" + folder.getFileName() + ").");
        return "Welt wiederhergestellt";
    }

    private void downloadAndExtract(JobContext ctx, BackupDownload download, Path target) {
        IOException lastError = null;
        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            ctx.checkCancelled();
            deleteRecursive(target);
            try {
                Files.createDirectories(target);
                extractZip(ctx, download, target);
                requireLevelDat(target);
                return;
            } catch (IOException ex) {
                lastError = ex;
                logger.warning("Download/Entpacken des Backups fehlgeschlagen (Versuch " + attempt + "/" + DOWNLOAD_ATTEMPTS + "): " + ex.getMessage());
                if (attempt < DOWNLOAD_ATTEMPTS) {
                    pause(5_000L);
                }
            }
        }
        throw new BackupException("Das Backup konnte nicht heruntergeladen werden: " + (lastError == null ? "unbekannt" : lastError.getMessage()));
    }

    private void extractZip(JobContext ctx, BackupDownload download, Path targetFolder) throws IOException {
        Path target = targetFolder.toAbsolutePath().normalize();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(download.url()))
            .timeout(DOWNLOAD_RESPONSE_TIMEOUT)
            .GET()
            .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BackupCancelledException();
        }
        if (response.statusCode() / 100 != 2) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode());
        }

        MessageDigest digest = newSha256();
        try (InputStream body = response.body();
             DigestInputStream digestStream = new DigestInputStream(body, digest);
             ZipInputStream zip = new ZipInputStream(digestStream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ctx.checkCancelled();
                Path file = target.resolve(entry.getName()).normalize();
                if (!file.startsWith(target)) {
                    throw new IOException("Ungültiger Pfad im Backup: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(file);
                    continue;
                }
                Files.createDirectories(file.getParent());
                Files.copy(zip, file, StandardCopyOption.REPLACE_EXISTING);
            }
            // Restliche Bytes (Zip-Verzeichnis) lesen, damit die Prüfsumme den ganzen Datenstrom abdeckt.
            digestStream.transferTo(OutputStream.nullOutputStream());
        }

        String expected = download.sha256();
        if (expected != null && !expected.isBlank() && !expected.equalsIgnoreCase(HexFormat.of().formatHex(digest.digest()))) {
            throw new IOException("Prüfsumme des Backups stimmt nicht");
        }
    }

    // ------------------------------------------------------------------
    // Wiederherstellung nach Absturz/Neustart
    // ------------------------------------------------------------------

    /**
     * Bringt Weltordner nach einem unterbrochenen Restore in einen konsistenten Zustand. Wird beim
     * Serverstart für jede Welt mit Restore-Status aufgerufen und nach fehlgeschlagenen Restores.
     */
    void recoverFolders(String world) {
        Path folder = worldFolder(world);
        Path tmp = tempFolder(world);
        Path old = previousFolder(world);
        Optional<State> state = states.read(world);

        try {
            if (state.isEmpty()) {
                // Nie Daten verlieren: fehlt die Welt, liegt aber der alte Stand noch da, zurücklegen.
                if (!Files.exists(folder) && Files.isDirectory(old)) {
                    stopWorldForFolderOperations(world);
                    moveDirectory(old, folder);
                }
                deleteRecursive(tmp);
                return;
            }

            switch (state.get().phase()) {
                case PREPARING -> {
                    deleteRecursive(tmp);
                    states.clear(world);
                }
                case SWAPPING -> {
                    // Do not infer that an existing world folder is empty or disposable.
                    stopWorldForFolderOperations(world);
                    throw new BackupException("Unterbrochener Ordner-Tausch erfordert Offline-Prüfung: " + world);
                }
                case DONE -> {
                    // Bleibt bestehen, bis der Auftrag erneut aufgenommen und abgeschlossen wird.
                }
            }
        } catch (IOException ex) {
            throw new BackupException("Wiederherstellung der Ordner fehlgeschlagen: " + world, ex);
        }
    }

    /** Beim Löschen einer Welt: alle Restore-Reste (Temp-Ordner, alter Stand, Status) entfernen. */
    void cleanupLeftovers(String world) {
        deleteRecursive(tempFolder(world));
        deleteRecursive(previousFolder(world));
        states.clear(world);
    }

    private void stopWorldForFolderOperations(String world) {
        if (!mainThread.call(() -> hooks.unloadWorldForRestore(world))) {
            throw new BackupException("Welt konnte vor dem Ordner-Tausch nicht entladen werden: " + world);
        }
    }

    // ------------------------------------------------------------------
    // Helfer
    // ------------------------------------------------------------------

    private void requireLevelDat(Path folder) {
        Path levelDat = folder.resolve("level.dat");
        try {
            if (!Files.isRegularFile(levelDat) || Files.size(levelDat) == 0L) {
                throw new BackupException("level.dat fehlt oder ist leer: " + levelDat);
            }
            // This only proves file readability. The full generation-manifest/NBT validator
            // described in ANALYSE.md is still required before production rollout.
            try (InputStream in = Files.newInputStream(levelDat)) {
                if (in.read() < 0) {
                    throw new BackupException("level.dat ist leer: " + levelDat);
                }
            }
        } catch (IOException ex) {
            throw new BackupException("level.dat ist nicht lesbar: " + levelDat, ex);
        }
    }

    private Path worldFolder(String world) {
        return worldContainer.resolve(world);
    }

    private Path tempFolder(String world) {
        return worldContainer.resolve(world + ".restore-tmp");
    }

    private Path previousFolder(String world) {
        return worldContainer.resolve(world + ".pre-restore");
    }

    private void writeState(String world, long jobId, Phase phase) {
        try {
            states.write(world, jobId, phase);
        } catch (IOException ex) {
            // Bei AccessDenied/NoSuchFile ist die Message nur der Pfad, daher den Typ mitloggen.
            throw new BackupException("Restore-Status konnte nicht gespeichert werden ("
                + ex.getClass().getSimpleName() + "): " + ex.getMessage(), ex);
        }
    }

    private void moveDirectory(Path source, Path target) throws IOException {
        Files.move(source, target);
    }

    private void moveDirectoryOrFail(Path source, Path target) {
        try {
            moveDirectory(source, target);
        } catch (IOException ex) {
            throw new BackupException("Weltordner konnte nicht getauscht werden: " + ex.getMessage(), ex);
        }
    }

    private boolean deleteRecursive(Path path) {
        if (!Files.exists(path)) {
            return true;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        } catch (IOException ex) {
            logger.warning("Ordner konnte nicht gelöscht werden (" + path + "): " + ex.getMessage());
            return false;
        }
    }

    private void runOnMainThread(Runnable action) {
        mainThread.call(() -> {
            action.run();
            return Boolean.TRUE;
        });
    }

    private MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", ex);
        }
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BackupCancelledException();
        }
    }
}
