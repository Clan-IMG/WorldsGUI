package net.clanimg.worldsGUI.guiconfig;

/**
 * Ein einzelner Slot in Zeilen-Notation (slot-1 bis slot-9). material/title können
 * Platzhalter wie %player_head% enthalten, die beim Rendern aufgelöst werden.
 */
public record GuiSlotDefinition(String material, String title, GuiAction action) {
}
