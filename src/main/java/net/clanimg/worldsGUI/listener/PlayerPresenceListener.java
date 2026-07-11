package net.clanimg.worldsGUI.listener;

import net.clanimg.worldsGUI.data.WorldsRepository;
import net.clanimg.worldsGUI.gui.GuiManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerPresenceListener implements Listener {
    private final JavaPlugin plugin;
    private final WorldsRepository repository;
    private final GuiManager guiManager;

    public PlayerPresenceListener(JavaPlugin plugin, WorldsRepository repository, GuiManager guiManager) {
        this.plugin = plugin;
        this.repository = repository;
        this.guiManager = guiManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        updatePresence(player.getName(), player.getWorld().getName(), true);
        guiManager.notifyCustomerActiveTicketOnJoin(player);
        Bukkit.getScheduler().runTask(plugin, () -> guiManager.ensurePersonalFlatWorldForJoin(player));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        updatePresence(player.getName(), player.getWorld().getName(), false);
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        updatePresence(player.getName(), player.getWorld().getName(), true);
    }

    private void updatePresence(String playerName, String worldName, boolean online) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
            repository.upsertPlayerPresence(playerName, worldName, online)
        );
    }
}
