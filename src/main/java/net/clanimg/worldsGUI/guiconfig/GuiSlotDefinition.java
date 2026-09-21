package net.clanimg.worldsGUI.guiconfig;

import java.util.List;

/**
 * Ein einzelner Slot in Zeilen-Notation (slot-1 bis slot-9). material/title/lore können
 * Platzhalter wie %player_head% enthalten, die beim Rendern aufgelöst werden.
 */
public record GuiSlotDefinition(String material, String title, List<String> lore, GuiAction action) {
    public GuiSlotDefinition {
        lore = lore == null ? List.of() : List.copyOf(lore);
    }

    public GuiSlotDefinition(String material, String title, GuiAction action) {
        this(material, title, List.of(), action);
    }
}
