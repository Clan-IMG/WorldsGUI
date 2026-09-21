package net.clanimg.worldsGUI.backup;

/**
 * Wird geworfen, wenn ein laufender Auftrag abgebrochen werden soll (Server fährt herunter oder der
 * Auftrag gehört laut API nicht mehr diesem Server).
 */
public final class BackupCancelledException extends RuntimeException {
    public BackupCancelledException() {
        super("Auftrag abgebrochen");
    }
}
