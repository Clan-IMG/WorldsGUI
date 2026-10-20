package net.clanimg.worldsGUI.guiconfig;

import java.util.List;

/**
 * Ein "slot-range"-Block in guis.yml. "from"/"to" sind absolute Inventar-Slot-Indizes
 * (0 bis gui-size - 1). "source" beschreibt den Daten-Provider (z.B. servers,
 * trusted-players, luckperms-players, world-backups-auto); kann null sein, wenn kein Provider definiert ist.
 * "lore" sind optionale Beschreibungszeilen je generiertem Item. "emptyMaterial"/"emptyTitle"
 * (optional) füllen Slots des Bereichs, für die es kein generiertes Item gibt.
 * "requiresPlugin" (optional, Feld "requires-plugin"): ist dieses Plugin nicht aktiv, liefert der
 * Bereich keine Items (nur ggf. empty-material) und keine Klick-Aktionen.
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
    GuiAction action,
    String requiresPlugin
) {
    public GuiSlotRangeDefinition {
        lore = lore == null ? List.of() : List.copyOf(lore);
        requiresPlugin = requiresPlugin == null || requiresPlugin.isBlank() ? null : requiresPlugin.trim();
    }

    public GuiSlotRangeDefinition(
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
        this(from, to, source, filter, material, title, lore, emptyMaterial, emptyTitle, action, null);
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
        this(from, to, source, filter, material, title, List.of(), null, null, action, null);
    }
}
