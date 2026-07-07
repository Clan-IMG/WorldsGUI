package net.clanimg.worldsGUI.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public record CreateOptionsHolder(String worldName, int returnPage, boolean voidWorld, boolean chunkyEnabled) implements InventoryHolder {
    @Override
    public Inventory getInventory() {
        return null;
    }
}
