package net.clanimg.worldsGUI.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public record IconSelectorHolder(String worldName, int page, int returnPage) implements InventoryHolder {
    @Override
    public Inventory getInventory() {
        return null;
    }
}
