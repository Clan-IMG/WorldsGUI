package net.clanimg.worldsGUI.backup;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.clanimg.worldsGUI.WorldsGUI;
import net.clanimg.worldsGUI.data.WorldsRepository;
import net.clanimg.worldsGUI.data.WorldsRepository.BackupApiResult;
import net.clanimg.worldsGUI.data.WorldsRepository.BackupJob;
import net.clanimg.worldsGUI.data.WorldsRepository.JobHeartbeat;
import net.clanimg.worldsGUI.data.WorldsRepository.WorldBackupOverview;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Backup-Warteschlange dieses Servers: holt regelmäßig Aufträge (nächtliche Auto-Backups, manuelle
 * Backups, Restores) für die hier liegenden Welten von der API ab, führt sie nacheinander aus,
 * meldet per Heartbeat, dass der Server noch daran arbeitet, und informiert Spieler über das Ergebnis.
 *
 * <p>Die Aufträge liegen dauerhaft in der API-Datenbank. Fällt der Server aus oder wird neu gestartet,
 * setzt die API einen Auftrag ohne Heartbeat zurück in die Warteschlange und er wird später (auch von
 * einem neu gestarteten Server) wieder aufgenommen. Ein Restore setzt dank {@link RestoreStateStore}
 * genau dort fort, wo er unterbrochen wurde.
 */
public final class BackupService {
    private static final long OVERVIEW_CACHE_MILLIS = 3_000L;
    private static final int FINISH_ATTEMPTS = 5;
    private static final long MAIN_THREAD_TIMEOUT_MINUTES = 5L;

    private record CachedOverview(long loadedAt, Optional<WorldBackupOverview> value) {
    }

    private final WorldsGUI plugin;
    private final WorldsRepository repository;
    private final BackupWorldHooks hooks;
    private final RestoreStateStore states;
    private final BackupRunner runner;
    private final Set<String> busyWorlds = ConcurrentHashMap.newKeySet();
    private final Map<Long, JobContext> activeJobs = new ConcurrentHashMap<>();
    private final Map<String, CachedOverview> overviewCache = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4, namedThreads("WorldsGUI-Backup"));
    private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(3, namedThreads("WorldsGUI-Backup-Timer"));
    private final List<ScheduledFuture<?>> scheduledTasks = new ArrayList<>();

    private volatile BackupSettings settings;
    private volatile boolean shuttingDown;
    private volatile boolean warnedMissingServerId;

    public BackupService(WorldsGUI plugin, WorldsRepository repository, BackupWorldHooks hooks) {
        this.plugin = plugin;
        this.repository = repository;
        this.hooks = hooks;
        this.settings = BackupSettings.from(plugin.getConfig());
        this.states = new RestoreStateStore(plugin.getDataFolder().toPath().resolve("backup-state"));

        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
        this.runner = new BackupRunner(
            plugin.getLogger(),
            repository,
            () -> settings,
            hooks,
            new BackupRunner.MainThread() {
                @Override
                public <T> T call(Supplier<T> action) {
                    return callOnMainThread(action);
                }
            },
            httpClient,
            states,
            busyWorlds
        );
    }

    /** Muss auf dem Hauptthread aufgerufen werden (Wiederherstellung unterbrochener Restores beim Start). */
    public void start() {
        settings = BackupSettings.from(plugin.getConfig());
        if (!settings.enabled()) {
            plugin.getLogger().info("Backups sind deaktiviert (config.yml: backup.enabled).");
            return;
        }

        for (String world : states.worldsWithState()) {
            plugin.getLogger().info("Unterbrochener Restore für Welt " + world + " gefunden, stelle Ordner wieder her ...");
            runner.recoverFolders(world);
        }

        scheduleTimers();
    }

    /** Übernimmt geänderte Werte aus config.yml (z.B. Abfrage-Intervalle) nach /worldsgui reload. */
    public void reload() {
        settings = BackupSettings.from(plugin.getConfig());
        cancelTimers();
        if (settings.enabled() && !shuttingDown) {
            scheduleTimers();
        }
    }

    public void shutdown() {
        shuttingDown = true;
        cancelTimers();
        timers.shutdownNow();
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Laufende Backup-Aufträge haben sich nicht rechtzeitig beendet.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        activeJobs.clear();
        overviewCache.clear();
    }

    public boolean isEnabled() {
        return settings.enabled();
    }

    public BackupSettings settings() {
        return settings;
    }

    /** Während eines Restore darf niemand die Welt betreten. */
    public boolean isWorldBusy(String worldName) {
        return worldName != null && busyWorlds.contains(worldName);
    }

    // ------------------------------------------------------------------
    // Anfragen aus GUI/Commands (laufen synchron, wie die übrigen Repository-Aufrufe)
    // ------------------------------------------------------------------

    public Optional<WorldBackupOverview> overview(String worldName) {
        long now = System.currentTimeMillis();
        CachedOverview cached = overviewCache.get(worldName);
        if (cached != null && now - cached.loadedAt() < OVERVIEW_CACHE_MILLIS) {
            return cached.value();
        }
        Optional<WorldBackupOverview> fresh = repository.loadBackupOverview(worldName);
        overviewCache.put(worldName, new CachedOverview(now, fresh));
        return fresh;
    }

    public BackupApiResult requestManualBackup(String worldName, String requestedBy) {
        overviewCache.remove(worldName);
        return repository.createBackupJob(worldName, "backup", null, requestedBy);
    }

    public BackupApiResult requestRestore(String worldName, long backupId, String requestedBy) {
        overviewCache.remove(worldName);
        return repository.createBackupJob(worldName, "restore", backupId, requestedBy);
    }

    public BackupApiResult cancelJob(String worldName, long jobId) {
        overviewCache.remove(worldName);
        return repository.cancelBackupJob(jobId, worldName);
    }

    public BackupApiResult deleteBackup(String worldName, long backupId) {
        overviewCache.remove(worldName);
        return repository.deleteBackup(backupId, worldName);
    }

    /** Beim Löschen einer Welt: Restore-Reste (Temp-Ordner, alter Stand, Status) entfernen. */
    public void cleanupLeftovers(String worldName) {
        overviewCache.remove(worldName);
        runner.cleanupLeftovers(worldName);
    }

    // ------------------------------------------------------------------
    // Warteschlange
    // ------------------------------------------------------------------

    private void scheduleTimers() {
        BackupSettings current = settings;
        scheduledTasks.add(timers.scheduleWithFixedDelay(this::pollJobs, 15L, current.pollIntervalSeconds(), TimeUnit.SECONDS));
        scheduledTasks.add(timers.scheduleWithFixedDelay(this::sendHeartbeats, current.heartbeatIntervalSeconds(), current.heartbeatIntervalSeconds(), TimeUnit.SECONDS));
        scheduledTasks.add(timers.scheduleWithFixedDelay(this::notifyRequesters, 20L, current.notifyIntervalSeconds(), TimeUnit.SECONDS));
    }

    private void cancelTimers() {
        for (ScheduledFuture<?> task : scheduledTasks) {
            task.cancel(false);
        }
        scheduledTasks.clear();
    }

    private void pollJobs() {
        if (shuttingDown) {
            return;
        }
        try {
            String server = hooks.localServerName();
            if (server == null || server.isBlank()) {
                if (!warnedMissingServerId) {
                    warnedMissingServerId = true;
                    plugin.getLogger().warning("Backups: Server-ID unbekannt (SIMPLECLOUD_SERVER_ID / api.local-server-name), Aufträge werden nicht abgeholt.");
                }
                return;
            }

            int free = settings.maxParallelJobs() - activeJobs.size();
            if (free <= 0) {
                return;
            }

            for (BackupJob job : repository.claimBackupJobs(server, free)) {
                JobContext context = new JobContext(job);
                activeJobs.put(job.id(), context);
                try {
                    executor.execute(() -> runJob(context));
                } catch (RejectedExecutionException ex) {
                    activeJobs.remove(job.id());
                    // Die API setzt den Auftrag nach Ablauf des Heartbeat-Timeouts selbst zurück.
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Backup-Aufträge konnten nicht abgeholt werden: " + ex.getMessage());
        }
    }

    private void runJob(JobContext context) {
        BackupJob job = context.job();
        boolean success = false;
        String message;
        try {
            plugin.getLogger().info("Backup-Auftrag " + job.id() + " (" + job.jobType() + ", " + job.worldName() + ") gestartet.");
            message = "restore".equals(job.jobType()) ? runner.runRestore(context) : runner.runBackup(context);
            success = true;
        } catch (BackupCancelledException ex) {
            // Server fährt herunter oder der Auftrag gehört nicht mehr uns: nicht melden, die API übernimmt.
            activeJobs.remove(job.id());
            return;
        } catch (BackupException ex) {
            message = ex.getMessage();
            plugin.getLogger().warning("Backup-Auftrag " + job.id() + " fehlgeschlagen: " + message);
        } catch (Throwable ex) {
            message = "Interner Fehler: " + ex.getClass().getSimpleName();
            plugin.getLogger().log(Level.SEVERE, "Backup-Auftrag " + job.id() + " abgebrochen", ex);
        }

        try {
            finishWithRetry(job, success, message);
        } finally {
            overviewCache.remove(job.worldName());
            activeJobs.remove(job.id());
        }
    }

    private void finishWithRetry(BackupJob job, boolean success, String message) {
        for (int attempt = 1; attempt <= FINISH_ATTEMPTS && !shuttingDown; attempt++) {
            if (repository.finishBackupJob(job.id(), hooks.localServerName(), success, message)) {
                return;
            }
            try {
                Thread.sleep(5_000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        plugin.getLogger().warning("Ergebnis von Backup-Auftrag " + job.id() + " konnte nicht gemeldet werden, die API setzt ihn zurück.");
    }

    private void sendHeartbeats() {
        if (shuttingDown || activeJobs.isEmpty()) {
            return;
        }
        try {
            String server = hooks.localServerName();
            for (JobContext context : activeJobs.values()) {
                JobHeartbeat heartbeat = repository.heartbeatBackupJob(context.job().id(), server, context.phase());
                if (heartbeat.reachable() && !heartbeat.owned()) {
                    plugin.getLogger().warning("Backup-Auftrag " + context.job().id() + " gehört laut API nicht mehr diesem Server, breche ab.");
                    context.cancel();
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Backup-Heartbeat fehlgeschlagen: " + ex.getMessage());
        }
    }

    private void notifyRequesters() {
        if (shuttingDown) {
            return;
        }
        try {
            List<BackupJob> jobs = repository.listUnnotifiedBackupJobs(30);
            if (jobs.isEmpty()) {
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                List<Long> delivered = new ArrayList<>();
                for (BackupJob job : jobs) {
                    Player player = job.requestedBy() == null ? null : Bukkit.getPlayerExact(job.requestedBy());
                    if (player == null || !player.isOnline()) {
                        // Spieler ist auf einem anderen Server oder offline: bleibt bis zu 1 Tag offen.
                        continue;
                    }
                    hooks.notifyJobResult(player, job);
                    delivered.add(job.id());
                }
                if (!delivered.isEmpty()) {
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> delivered.forEach(repository::markBackupJobNotified));
                }
            });
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Backup-Benachrichtigungen fehlgeschlagen: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Hauptthread
    // ------------------------------------------------------------------

    private <T> T callOnMainThread(Supplier<T> action) {
        if (Bukkit.isPrimaryThread()) {
            return action.get();
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(action.get());
            } catch (Throwable ex) {
                future.completeExceptionally(ex);
            }
        });

        try {
            return future.get(MAIN_THREAD_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BackupCancelledException();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(cause);
        } catch (TimeoutException ex) {
            throw new BackupException("Der Server hat nicht rechtzeitig geantwortet");
        }
    }

    private static ThreadFactory namedThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
