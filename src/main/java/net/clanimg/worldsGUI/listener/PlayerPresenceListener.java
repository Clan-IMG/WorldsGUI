package net.clanimg.worldsGUI.listener;

import net.clanimg.worldsGUI.data.WorldsRepository;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerPresenceListener implements Listener {
    private final WorldsRepository repository;

    public PlayerPresenceListener(WorldsRepository repository) {
        this.repository = repository;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        repository.upsertPlayerPresence(player.getName(), player.getWorld().getName(), true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        repository.upsertPlayerPresence(player.getName(), player.getWorld().getName(), false);
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        repository.upsertPlayerPresence(player.getName(), player.getWorld().getName(), true);
    }
}
