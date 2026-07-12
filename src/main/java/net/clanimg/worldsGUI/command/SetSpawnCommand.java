package net.clanimg.worldsGUI.command;

import net.clanimg.worldsGUI.gui.GuiManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class SetSpawnCommand implements CommandExecutor {
    private final GuiManager guiManager;

    public SetSpawnCommand(GuiManager guiManager) {
        this.guiManager = guiManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            guiManager.sendWithPrefix(sender, "command.only-player", "Dieser Befehl ist nur für Spieler.");
            return true;
        }
        guiManager.executeSetSpawn(player);
        return true;
    }
}
