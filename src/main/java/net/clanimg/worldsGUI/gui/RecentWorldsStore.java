package net.clanimg.worldsGUI.gui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Merkt sich pro Spieler die zuletzt besuchten Welten (neueste zuerst) für das "Zuletzt besucht"-GUI.
 * Gespeichert in plugins/WorldsGUI/recent-worlds.yml auf dem jeweiligen Server.
 */
public final class RecentWorldsStore {
    public static final int MAX_ENTRIES = 9;

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, List<String>> recentByPlayer = new ConcurrentHashMap<>();
    private final AtomicLong saveVersion = new AtomicLong();

    public RecentWorldsStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "recent-worlds.yml");
        load();
    }

    public List<String> recent(UUID playerId) {
        List<String> worlds = recentByPlayer.get(playerId);
        return worlds == null ? List.of() : List.copyOf(worlds);
    }

    public void record(UUID playerId, String worldName) {
        if (playerId == null || worldName == null || worldName.isBlank()) {
            return;
        }
        List<String> updated = new ArrayList<>(recent(playerId));
        updated.removeIf(existing -> existing.equalsIgnoreCase(worldName));
        updated.add(0, worldName);
        if (updated.size() > MAX_ENTRIES) {
            updated = new ArrayList<>(updated.subList(0, MAX_ENTRIES));
        }
        recentByPlayer.put(playerId, updated);
        saveAsync();
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            try {
                recentByPlayer.put(UUID.fromString(key), new ArrayList<>(yaml.getStringList(key)));
            } catch (IllegalArgumentException ignored) {
                // Ungültiger Eintrag (keine UUID) - überspringen.
            }
        }
    }

    private void saveAsync() {
        YamlConfiguration yaml = new YamlConfiguration();
        recentByPlayer.forEach((playerId, worlds) -> yaml.set(playerId.toString(), worlds));
        long version = saveVersion.incrementAndGet();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (file) {
                if (version != saveVersion.get()) {
                    return; // ein neuerer Stand wird gleich gespeichert
                }
                try {
                    yaml.save(file);
                } catch (IOException ex) {
                    plugin.getLogger().warning("recent-worlds.yml konnte nicht gespeichert werden: " + ex.getMessage());
                }
            }
        });
    }
}
