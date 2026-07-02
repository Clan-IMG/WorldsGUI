package net.clanimg.worldsGUI.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public record SettingsHolder(String worldName, int returnPage) implements InventoryHolder {
    @Override
    public Inventory getInventory() {
        return null;
    }
}
