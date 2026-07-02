package net.clanimg.worldsGUI.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public record MainHolder(ViewMode mode, int page) implements InventoryHolder {
    @Override
    public Inventory getInventory() {
        return null;
    }
}
