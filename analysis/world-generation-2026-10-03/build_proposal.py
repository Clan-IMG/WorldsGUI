from pathlib import Path
import difflib

root = Path.cwd()
out = root / 'analysis/world-generation-2026-10-03'
base = Path('src/main/java/net/clanimg/worldsGUI')
changed = {}

def edit(relative, transform):
    path = base / relative
    before = (root / path).read_text(encoding='utf-8')
    after = transform(before)
    assert after != before, str(path)
    changed[path] = (before, after)
    dest = out / 'proposal-src' / path
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(after, encoding='utf-8', newline='\n')

def replace_once(text, old, new):
    assert text.count(old) == 1, (text.count(old), old[:100])
    return text.replace(old, new, 1)

def gui(text):
    text = replace_once(text, '''                    if (Bukkit.getWorld(entry.worldName()) != null) {
                        continue;
                    }
                    createWorldFromMetadata(entry);''', '''                    if (backupService.isWorldBusy(entry.worldName())
                        || Bukkit.getWorld(entry.worldName()) != null) {
                        continue;
                    }
                    createWorldFromMetadata(entry);''')
    text = replace_once(text, '''        String worldName = entry.worldName();
        String worldKey = worldName.toLowerCase(Locale.ROOT);
        String ownerUuid = entry.ownerUuid();''', '''        String worldName = entry.worldName();
        String worldKey = worldName.toLowerCase(Locale.ROOT);
        if (backupService.isWorldBusy(worldName)) {
            metadataWorldCreationsInProgress.remove(worldKey);
            return;
        }

        File existingFolder = new File(Bukkit.getWorldContainer(), worldName);
        if (existingFolder.exists()) {
            // Existing worlds must use their registered environment and generator.
            // Do not run the new-world creator or re-import an existing MV entry.
            File levelDat = new File(existingFolder, "level.dat");
            if (!existingFolder.isDirectory() || !levelDat.isFile() || levelDat.length() == 0L) {
                plugin.getLogger().severe("Bestehende Welt ohne level.dat wird nicht geladen: " + worldName);
                metadataWorldCreationsInProgress.remove(worldKey);
                return;
            }
            Plugin mvCore = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
            if (mvCore == null || !mvCore.isEnabled()) {
                plugin.getLogger().warning("Multiverse ist nicht bereit; Welt bleibt ungeladen: " + worldName);
            } else {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv load " + worldName);
                World loaded = Bukkit.getWorld(worldName);
                if (loaded == null) {
                    plugin.getLogger().warning("Bestehende Welt konnte nicht sicher geladen werden: " + worldName);
                } else {
                    refreshWorldGuardProtection(worldName);
                }
            }
            metadataWorldCreationsInProgress.remove(worldKey);
            return;
        }
        String ownerUuid = entry.ownerUuid();''')
    text = replace_once(text, '''        if (worldName == null || worldName.isBlank() || Bukkit.getWorld(worldName) != null) {''', '''        if (worldName == null || worldName.isBlank() || backupService.isWorldBusy(worldName)
            || Bukkit.getWorld(worldName) != null) {''')
    text = replace_once(text, '''        int nextIndex = resolveMetadataWorldIndex(player, worldName);
        String targetServer''', '''        if (backupService.isWorldBusy(worldName)) {
            sendWithPrefix(player, "backup.world-busy", "Diese Welt wird gerade wiederhergestellt.");
            return;
        }
        int nextIndex = resolveMetadataWorldIndex(player, worldName);
        String targetServer''')
    text = replace_once(text, '''        CloneTarget target = resolveCloneTarget(player, targetPlayerName);
        if (target == null) {
            return;
        }

        String cloneWorldName''', '''        if (backupService.isWorldBusy(sourceEntry.worldName())) {
            sendWithPrefix(player, "backup.world-busy", "Diese Welt wird gerade wiederhergestellt.");
            return;
        }
        CloneTarget target = resolveCloneTarget(player, targetPlayerName);
        if (target == null) {
            return;
        }

        String cloneWorldName''')
    text = replace_once(text, '''        World world = Bukkit.getWorld(normalizedWorld);
        if (world == null) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv import " + normalizedWorld + " normal");''', '''        if (backupService.isWorldBusy(normalizedWorld)) {
            sendWithPrefix(player, "backup.world-busy", "Diese Welt wird gerade wiederhergestellt.");
            return;
        }
        File importFolder = new File(Bukkit.getWorldContainer(), normalizedWorld);
        File levelDat = new File(importFolder, "level.dat");
        if (!levelDat.isFile() || levelDat.length() == 0L) {
            send(player, "import.load-failed", "%world%", normalizedWorld);
            return;
        }
        World world = Bukkit.getWorld(normalizedWorld);
        if (world == null) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv import " + normalizedWorld + " normal");''')
    return text

edit(Path('gui/GuiManager.java'), gui)

def service(text):
    return replace_once(text, '''        return worldName != null && busyWorlds.contains(worldName);''', '''        return worldName != null && (busyWorlds.stream().anyMatch(worldName::equalsIgnoreCase)
            || states.hasState(worldName));''')
edit(Path('backup/BackupService.java'), service)

def store(text):
    text = replace_once(text, '''        } catch (IOException | IllegalArgumentException ex) {
            return Optional.empty();
        }''', '''        } catch (IOException | IllegalArgumentException ex) {
            throw new BackupException("Restore-Status ist nicht lesbar: " + file, ex);
        }''')
    text = replace_once(text, '''    Optional<State> read(String worldName) {''', '''    boolean hasState(String worldName) {
        if (Files.exists(fileFor(worldName))) {
            return true;
        }
        return worldsWithState().stream().anyMatch(worldName::equalsIgnoreCase);
    }

    Optional<State> read(String worldName) {''')
    text = replace_once(text, '''        } catch (IOException ignored) {
            // Kein Zugriff: dann gibt es nichts wiederherzustellen.
        }''', '''        } catch (IOException ex) {
            throw new BackupException("Restore-Statusverzeichnis ist nicht lesbar: " + directory, ex);
        }''')
    return text
edit(Path('backup/RestoreStateStore.java'), store)

def runner(text):
    text = text.replace('import java.nio.file.AccessDeniedException;\n', '')
    text = text.replace('import java.nio.file.NoSuchFileException;\n', '')
    text = replace_once(text, '''            ctx.checkCancelled();
            WorldFingerprint fingerprint = fingerprintOf(folder);''', '''            ctx.checkCancelled();
            requireLevelDat(folder);
            WorldFingerprint fingerprint = fingerprintOf(folder);''')
    text = replace_once(text, '''                InputStream in;
                try {
                    in = Files.newInputStream(file);
                } catch (NoSuchFileException | AccessDeniedException ex) {
                    // Datei ist zwischen Auflisten und Lesen verschwunden bzw. gesperrt: überspringen.
                    return FileVisitResult.CONTINUE;
                }

                try (in) {''', '''                // A successfully uploaded archive must not silently omit world files.
                try (InputStream in = Files.newInputStream(file)) {''')
    text = replace_once(text, '''            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }''', '''            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                throw exc;
            }''')
    text = replace_once(text, '''    String runRestore(JobContext ctx) {
        BackupJob job = ctx.job();''', '''    String runRestore(JobContext ctx) {
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
        BackupJob job = ctx.job();''')
    text = replace_once(text, '''        deleteRecursive(tmp);
        deleteRecursive(old);

        ctx.phase("download");''', '''        if (Files.exists(old)) {
            throw new BackupException("Vorheriger Weltstand bleibt erhalten: " + old
                + ". Vor einem weiteren Restore offline archivieren.");
        }
        deleteRecursive(tmp);

        ctx.phase("download");''')
    text = replace_once(text, '''        busyWorlds.add(world);
        try {
            if (Files.isDirectory(folder)) {''', '''        try {
            if (Files.isDirectory(folder)) {''')
    text = replace_once(text, '''            writeState(world, job.id(), Phase.SWAPPING);
            if (Files.exists(folder)) {''', '''            if (mainThread.call(() -> Bukkit.getWorld(world) != null)) {
                throw new BackupException("Welt wurde während des Restores erneut geladen; kein Ordner-Tausch");
            }
            requireLevelDat(tmp);
            writeState(world, job.id(), Phase.SWAPPING);
            if (Files.exists(folder)) {''')
    text = replace_once(text, '''        } catch (RuntimeException ex) {
            restoreConsistencyAfterFailure(world);
            throw ex;
        } finally {
            busyWorlds.remove(world);
        }''', '''        } catch (RuntimeException ex) {
            // Retain state and both world versions; no speculative load or destructive rollback.
            logger.severe("Restore abgebrochen; Status und Weltstände bleiben zur Offline-Prüfung erhalten: " + world);
            throw ex;
        }''')
    text = replace_once(text, '''        ctx.phase("load");
        if (!mainThread.call(() -> hooks.loadWorld(world))) {
            rollbackAfterFailedLoad(world);
            throw new BackupException("Die wiederhergestellte Welt konnte nicht geladen werden, der vorherige Stand wurde wiederhergestellt");
        }''', '''        ctx.phase("load");
        requireLevelDat(folder);
        if (!mainThread.call(() -> {
            if (Bukkit.getWorld(world) != null) {
                throw new BackupException("Welt ist vor dem vorgesehenen Restore-Laden bereits geladen");
            }
            return hooks.loadWorld(world);
        })) {
            throw new BackupException("Welt konnte nicht geladen werden; beide Stände und Restore-Status bleiben erhalten");
        }''')
    text = replace_once(text, '''        if (Files.exists(old) && !deleteRecursive(old)) {
            logger.warning("Der vorherige Weltstand konnte nicht gelöscht werden: " + old);
        }''', '''        if (Files.exists(old)) {
            logger.info("Vorheriger Weltstand bleibt erhalten: " + old);
        }''')
    start = text.index('    private void rollbackAfterFailedLoad(String world) {')
    end = text.index('    private void downloadAndExtract(', start)
    text = text[:start] + text[end:]
    text = replace_once(text, '''                extractZip(ctx, download, target);
                return;''', '''                extractZip(ctx, download, target);
                requireLevelDat(target);
                return;''')
    start = text.index('                case SWAPPING -> {')
    end = text.index('                case DONE -> {', start)
    text = text[:start] + '''                case SWAPPING -> {
                    // Do not infer that an existing world folder is empty or disposable.
                    stopWorldForFolderOperations(world);
                    throw new BackupException("Unterbrochener Ordner-Tausch erfordert Offline-Prüfung: " + world);
                }
''' + text[end:]
    text = replace_once(text, '''        } catch (IOException ex) {
            logger.severe("Wiederherstellung der Ordner von Welt " + world + " fehlgeschlagen: " + ex.getMessage());
        }''', '''        } catch (IOException ex) {
            throw new BackupException("Wiederherstellung der Ordner fehlgeschlagen: " + world, ex);
        }''')
    text = replace_once(text, '''        try {
            mainThread.call(() -> hooks.unloadWorldForRestore(world));
        } catch (RuntimeException ex) {
            logger.warning("Welt " + world + " konnte vor dem Ordner-Tausch nicht entladen werden: " + ex.getMessage());
        }''', '''        if (!mainThread.call(() -> hooks.unloadWorldForRestore(world))) {
            throw new BackupException("Welt konnte vor dem Ordner-Tausch nicht entladen werden: " + world);
        }''')
    text = replace_once(text, '''    private Path worldFolder(String world) {''', '''    private void requireLevelDat(Path folder) {
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

    private Path worldFolder(String world) {''')
    return text
edit(Path('backup/BackupRunner.java'), runner)

def fingerprint(text):
    return replace_once(text, '''            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // Datei ist zwischen Auflisten und Lesen verschwunden (z.B. Chunk-Datei wurde ersetzt).
                return FileVisitResult.CONTINUE;
            }''', '''            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                throw exc;
            }''')
edit(Path('backup/WorldFingerprint.java'), fingerprint)

patch = ''.join(''.join(difflib.unified_diff(before.splitlines(keepends=True), after.splitlines(keepends=True),
    fromfile='a/' + path.as_posix(), tofile='b/' + path.as_posix())) for path, (before, after) in changed.items())
(out / 'restore-safety.patch').write_text(patch, encoding='utf-8', newline='\n')
print('Unapplied proposal:', len(changed), 'files;', len(patch.splitlines()), 'diff lines')
