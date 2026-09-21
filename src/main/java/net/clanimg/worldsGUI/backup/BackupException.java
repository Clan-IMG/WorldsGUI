package net.clanimg.worldsGUI.backup;

/**
 * Erwartbarer Fehler eines Backup-/Restore-Auftrags. Die Nachricht ist für Spieler gedacht
 * (wird im Auftrag gespeichert und in der Fehlermeldung im Chat angezeigt).
 */
public final class BackupException extends RuntimeException {
    public BackupException(String message) {
        super(message);
    }

    public BackupException(String message, Throwable cause) {
        super(message, cause);
    }
}
