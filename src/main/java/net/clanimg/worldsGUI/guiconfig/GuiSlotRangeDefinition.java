package net.clanimg.worldsGUI.guiconfig;

/**
 * Ein "slot-range"-Block in guis.yml. "from"/"to" sind absolute Inventar-Slot-Indizes
 * (0 bis gui-size - 1). "source" beschreibt den Daten-Provider (z.B. servers,
 * trusted-players, luckperms-players); kann null sein, wenn kein Provider definiert ist.
 */
public record GuiSlotRangeDefinition(
    int from,
    int to,
    String source,
    String filter,
    String material,
    String title,
    GuiAction action
) {
}
