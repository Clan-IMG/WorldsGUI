package net.clanimg.worldsGUI.listener;

import net.clanimg.worldsGUI.gui.GuiManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public final class InventoryListener implements Listener {
    private final GuiManager guiManager;

    public InventoryListener(GuiManager guiManager) {
        this.guiManager = guiManager;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        guiManager.handleInventoryClick(event);
    }
}
