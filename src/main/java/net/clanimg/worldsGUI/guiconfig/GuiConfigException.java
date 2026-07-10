package net.clanimg.worldsGUI.guiconfig;

/**
 * Wird geworfen, wenn guis.yml nicht gelesen werden kann oder kritische
 * Validierungsfehler enthält (z.B. überlappende Slot-Bereiche, unbekannte
 * gui-id-Referenzen, fehlende Pflichtfelder).
 */
public final class GuiConfigException extends RuntimeException {
    public GuiConfigException(String message) {
        super(message);
    }

    public GuiConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
