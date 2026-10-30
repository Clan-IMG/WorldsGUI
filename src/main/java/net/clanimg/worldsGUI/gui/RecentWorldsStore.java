package net.clanimg.worldsGUI.gui;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.clanimg.worldsGUI.data.WorldsRepository;
import net.clanimg.worldsGUI.data.WorldsRepository.PlayerSettingsLookup;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * "Zuletzt besucht" und die gewählte Sortierung von "Meine Welten" - netzwerkweit in der worldsgui-api gespeichert
 * ({@code /players/<uuid>/world-visits} und {@code /players/<uuid>/settings}), damit jeder Server dasselbe zeigt.
 *
 * <p>Ein Spieler ist immer nur auf einem Server: Beim Join wird sein Stand einmal geladen, danach hält dieser Server
 * ihn selbst aktuell (Besuch beim Betreten einer Welt, Sortierung beim Umschalten) und schreibt jede Änderung sofort
 * zurück. Die frühere lokale {@code recent-worlds.yml} wird einmalig in die API übernommen.
 */
public final class RecentWorldsStore {
    private static final String LEGACY_FILE = "recent-worlds.yml";
    /** Abstand der künstlichen Zeitstempel beim Übernehmen der alten Liste (neueste zuerst). */
    private static final long LEGACY_STEP_MS = 60_000L;
    /** Konnte der Stand nicht geladen werden (API weg), frühestens nach dieser Zeit erneut versuchen. */
    private static final long RELOAD_RETRY_MS = 30_000L;

    private final JavaPlugin plugin;
    private final WorldsRepository repository;
    private final Consumer<UUID> onLoadedChange;
    private final Map<UUID, PlayerData> dataByPlayer = new ConcurrentHashMap<>();

    /**
     * @param onLoadedChange wird auf dem Server-Thread aufgerufen, wenn der nachgeladene Stand die Anzeige ändert
     */
    public RecentWorldsStore(JavaPlugin plugin, WorldsRepository repository, Consumer<UUID> onLoadedChange) {
        this.plugin = plugin;
        this.repository = repository;
        this.onLoadedChange = onLoadedChange;
        importLegacyFile();
        for (Player online : Bukkit.getOnlinePlayers()) {
            load(online.getUniqueId()); // nach /reload
        }
    }

    /** Beim Join: Stand des Spielers aus der API holen. */
    public void load(UUID playerId) {
        startLoad(playerId, dataByPlayer.computeIfAbsent(playerId, id -> new PlayerData()));
    }

    /** Beim Quit: der nächste Server lädt den Stand selbst. */
    public void forget(UUID playerId) {
        dataByPlayer.remove(playerId);
    }

    /** Zeitpunkt (Epoch-Millis) des letzten Besuchs je Welt, Schlüssel in Kleinbuchstaben. */
    public Map<String, Long> lastVisits(UUID playerId) {
        PlayerData data = data(playerId);
        synchronized (data) {
            return new HashMap<>(data.visits);
        }
    }

    public void record(UUID playerId, String worldName) {
        if (playerId == null || worldName == null || worldName.isBlank()) {
            return;
        }
        PlayerData data = data(playerId);
        synchronized (data) {
            data.visits.put(worldName.toLowerCase(Locale.ROOT), System.currentTimeMillis());
        }
        runAsync(() -> repository.recordWorldVisit(playerId.toString(), worldName, null));
    }

    public WorldSortMode sortMode(UUID playerId) {
        PlayerData data = data(playerId);
        synchronized (data) {
            return data.sortMode == null ? WorldSortMode.DEFAULT : data.sortMode;
        }
    }

    public void setSortMode(UUID playerId, WorldSortMode mode) {
        if (playerId == null || mode == null) {
            return;
        }
        PlayerData data = data(playerId);
        synchronized (data) {
            data.sortMode = mode;
            data.sortSetLocally = true; // ein noch laufendes Laden darf das nicht mehr überschreiben
        }
        runAsync(() -> repository.updateWorldSort(playerId.toString(), mode.key()));
    }

    private PlayerData data(UUID playerId) {
        PlayerData data = dataByPlayer.computeIfAbsent(playerId, id -> new PlayerData());
        boolean retry;
        synchronized (data) {
            retry = !data.loaded && !data.loading && System.currentTimeMillis() - data.lastAttempt >= RELOAD_RETRY_MS;
        }
        if (retry) {
            startLoad(playerId, data);
        }
        return data;
    }

    private void startLoad(UUID playerId, PlayerData data) {
        synchronized (data) {
            if (data.loading) {
                return;
            }
            data.loading = true;
            data.lastAttempt = System.currentTimeMillis();
        }
        runAsync(() -> {
            String id = playerId.toString();
            Optional<Map<String, Long>> visits = repository.fetchWorldVisits(id);
            PlayerSettingsLookup settings = repository.fetchPlayerSettings(id);
            boolean changed;
            synchronized (data) {
                data.loading = false;
                data.loaded = visits.isPresent() && !settings.failed();
                WorldSortMode before = data.sortMode;
                if (!settings.failed() && !data.sortSetLocally) {
                    data.sortMode = WorldSortMode.parse(settings.worldSort()).orElse(null);
                }
                boolean visitsChanged = visits.isPresent() && mergeVisits(data, visits.get());
                WorldSortMode effective = data.sortMode == null ? WorldSortMode.DEFAULT : data.sortMode;
                changed = before != data.sortMode || (visitsChanged && effective.usesVisits());
            }
            if (changed && plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> onLoadedChange.accept(playerId));
            }
        });
    }

    /** Neuerer Zeitpunkt gewinnt (ein Besuch, der während des Ladens gemeldet wurde, bleibt erhalten). */
    private static boolean mergeVisits(PlayerData data, Map<String, Long> visits) {
        boolean changed = false;
        for (Map.Entry<String, Long> visit : visits.entrySet()) {
            String key = visit.getKey().toLowerCase(Locale.ROOT);
            Long previous = data.visits.get(key);
            if (previous == null || previous < visit.getValue()) {
                data.visits.put(key, visit.getValue());
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Übernimmt die alte, lokale recent-worlds.yml (je Spieler eine Liste der zuletzt besuchten Welten, neueste
     * zuerst) in die API und benennt sie danach in recent-worlds.yml.imported um. Ist die API nicht erreichbar oder
     * noch zu alt, bleibt die Datei liegen und wird beim nächsten Start erneut übernommen; ein späterer echter Besuch
     * wird dabei nie überschrieben.
     */
    private void importLegacyFile() {
        File file = new File(plugin.getDataFolder(), LEGACY_FILE);
        if (!file.isFile()) {
            return;
        }
        runAsync(() -> {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            long newest = file.lastModified();
            int sent = 0;
            boolean complete = true;
            for (String key : yaml.getKeys(false)) {
                UUID playerId;
                try {
                    playerId = UUID.fromString(key);
                } catch (IllegalArgumentException ignored) {
                    continue; // keine UUID
                }
                List<String> worlds = yaml.getStringList(key);
                Map<String, Long> imported = new HashMap<>();
                for (int i = 0; i < worlds.size(); i++) {
                    long at = newest - i * LEGACY_STEP_MS;
                    if (!repository.recordWorldVisit(playerId.toString(), worlds.get(i), at)) {
                        complete = false;
                        continue;
                    }
                    imported.put(worlds.get(i), at);
                    sent++;
                }
                PlayerData data = dataByPlayer.get(playerId);
                if (data != null) {
                    synchronized (data) {
                        mergeVisits(data, imported);
                    }
                }
            }
            if (!complete) {
                plugin.getLogger().warning(LEGACY_FILE + " konnte nicht vollständig in die API übernommen werden"
                    + " - neuer Versuch beim nächsten Start.");
                return;
            }
            File target = new File(file.getParentFile(), LEGACY_FILE + ".imported");
            if (file.renameTo(target)) {
                plugin.getLogger().info(LEGACY_FILE + ": " + sent + " Besuche in die API übernommen (Datei umbenannt in "
                    + target.getName() + ").");
            } else {
                plugin.getLogger().warning(LEGACY_FILE + " wurde übernommen, konnte aber nicht umbenannt werden.");
            }
        });
    }

    private void runAsync(Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    private static final class PlayerData {
        private final Map<String, Long> visits = new HashMap<>();
        private WorldSortMode sortMode;
        private boolean sortSetLocally;
        private boolean loaded;
        private boolean loading;
        private long lastAttempt;
    }
}
