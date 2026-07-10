package net.clanimg.worldsGUI.guiconfig;

/**
 * Bildet die Zeilen-Notation (slot-1..slot-9) aus guis.yml auf absolute Inventar-Slot-Indizes ab.
 * Wird sowohl von der Validierung (GuiConfigLoader) als auch später vom Rendering benutzt.
 */
public final class SlotMapper {
    private SlotMapper() {
    }

    public static boolean isValidGuiSize(int size) {
        return size >= 9 && size <= 54 && size % 9 == 0;
    }

    /**
     * @param rowSlot  1 bis 9 (Zeilen-Notation aus guis.yml)
     * @param position bottom (letzte Zeile) oder middle (mittlere Zeile)
     * @param guiSize  gültige Inventargröße (Vielfaches von 9, 9-54)
     * @return absoluter Inventar-Slot-Index (0-basiert)
     */
    public static int mapRowSlotToAbsolute(int rowSlot, GuiPosition position, int guiSize) {
        if (rowSlot < 1 || rowSlot > 9) {
            throw new IllegalArgumentException("rowSlot muss zwischen 1 und 9 liegen, war: " + rowSlot);
        }
        if (!isValidGuiSize(guiSize)) {
            throw new IllegalArgumentException("Ungültige gui-size: " + guiSize);
        }

        int rows = guiSize / 9;
        int rowIndex = switch (position) {
            case BOTTOM -> rows - 1;
            case MIDDLE -> (rows - 1) / 2;
        };
        return rowIndex * 9 + (rowSlot - 1);
    }
}
