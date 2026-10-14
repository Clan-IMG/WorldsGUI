package net.clanimg.worldsGUI.guiconfig;

import java.util.List;

/**
 * Ein einzelner Slot in Zeilen-Notation (slot-1 bis slot-9). material/title/lore können
 * Platzhalter wie %player_head% enthalten, die beim Rendern aufgelöst werden.
 * "requiresPlugin" (optional, Feld "requires-plugin"): ist dieses Plugin nicht aktiv, wird der
 * Slot wie ein leerer Slot (Glasscheibe ohne Aktion) dargestellt.
 */
public record GuiSlotDefinition(String material, String title, List<String> lore, GuiAction action, String requiresPlugin) {
    public GuiSlotDefinition {
        lore = lore == null ? List.of() : List.copyOf(lore);
        requiresPlugin = requiresPlugin == null || requiresPlugin.isBlank() ? null : requiresPlugin.trim();
    }

    public GuiSlotDefinition(String material, String title, List<String> lore, GuiAction action) {
        this(material, title, lore, action, null);
    }

    public GuiSlotDefinition(String material, String title, GuiAction action) {
        this(material, title, List.of(), action, null);
    }
}
