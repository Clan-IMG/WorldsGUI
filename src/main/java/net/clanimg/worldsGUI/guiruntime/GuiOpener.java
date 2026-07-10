package net.clanimg.worldsGUI.guiruntime;

import org.bukkit.entity.Player;

/**
 * Abstraktion zum tatsächlichen Öffnen eines GUIs über eine gui-id.
 * Wird von der Bukkit-Inventory-Rendering-Schicht (GuiManager) implementiert.
 */
public interface GuiOpener {
    void openGui(Player player, String guiId);
}
