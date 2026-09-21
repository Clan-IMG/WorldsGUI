package net.clanimg.worldsGUI.backup;

import net.clanimg.worldsGUI.data.WorldsRepository.BackupJob;

/** Laufzeitzustand eines von diesem Server ausgeführten Auftrags (geteilt zwischen Worker und Heartbeat). */
final class JobContext {
    private final BackupJob job;
    private volatile String phase = "claimed";
    private volatile boolean cancelled;

    JobContext(BackupJob job) {
        this.job = job;
    }

    BackupJob job() {
        return job;
    }

    String phase() {
        return phase;
    }

    void phase(String phase) {
        this.phase = phase;
    }

    void cancel() {
        this.cancelled = true;
    }

    void checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new BackupCancelledException();
        }
    }
}
