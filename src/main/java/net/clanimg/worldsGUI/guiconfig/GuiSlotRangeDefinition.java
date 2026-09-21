package net.clanimg.worldsGUI.guiconfig;

import java.util.List;

/**
 * Ein "slot-range"-Block in guis.yml. "from"/"to" sind absolute Inventar-Slot-Indizes
 * (0 bis gui-size - 1). "source" beschreibt den Daten-Provider (z.B. servers,
 * trusted-players, luckperms-players, world-backups-auto); kann null sein, wenn kein Provider definiert ist.
 * "lore" sind optionale Beschreibungszeilen je generiertem Item. "emptyMaterial"/"emptyTitle"
 * (optional) füllen Slots des Bereichs, für die es kein generiertes Item gibt.
 */
public record GuiSlotRangeDefinition(
    int from,
    int to,
    String source,
    String filter,
    String material,
    String title,
    List<String> lore,
    String emptyMaterial,
    String emptyTitle,
    GuiAction action
) {
    public GuiSlotRangeDefinition {
        lore = lore == null ? List.of() : List.copyOf(lore);
    }

    public GuiSlotRangeDefinition(
        int from,
        int to,
        String source,
        String filter,
        String material,
        String title,
        GuiAction action
    ) {
        this(from, to, source, filter, material, title, List.of(), null, null, action);
    }
}
