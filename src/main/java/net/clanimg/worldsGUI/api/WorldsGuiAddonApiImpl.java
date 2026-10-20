package net.clanimg.worldsGUI.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.clanimg.worldsGUI.data.WorldsRepository;
import net.clanimg.worldsGUI.gui.GuiManager;
import net.clanimg.worldsGUI.model.WorldEntry;

/**
 * Implementierung der Addon-API. Welt-Abfragen laufen über die (blockierende) HTTP-API von
 * {@link WorldsRepository} und werden daher auf einem kleinen, eigenen Executor ausgeführt -
 * niemals auf dem Main-Thread.
 */
public final class WorldsGuiAddonApiImpl implements WorldsGuiAddonApi {
    public static final int API_VERSION = 1;

    private final WorldsRepository repository;
    private final GuiManager guiManager;
    private final ExecutorService executor;

    public WorldsGuiAddonApiImpl(WorldsRepository repository, GuiManager guiManager) {
        this.repository = repository;
        this.guiManager = guiManager;
        AtomicInteger threadCounter = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            2,
            2,
            30L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(256),
            runnable -> {
                Thread thread = new Thread(runnable, "WorldsGUI-AddonApi-" + threadCounter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        );
        pool.allowCoreThreadTimeOut(true);
        this.executor = pool;
    }

    @Override
    public int apiVersion() {
        return API_VERSION;
    }

    @Override
    public String localServerName() {
        String name = guiManager.resolveLocalServerIdentifier();
        return name == null ? "" : name;
    }

    @Override
    public CompletableFuture<Optional<Map<String, Object>>> lookupWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        try {
            return CompletableFuture.supplyAsync(
                () -> repository.findByWorldName(worldName.trim()).map(WorldsGuiAddonApiImpl::toMap),
                executor
            );
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    /** Beendet den Executor (onDisable). */
    public void shutdown() {
        executor.shutdownNow();
    }

    private static Map<String, Object> toMap(WorldEntry entry) {
        Map<String, Object> map = new HashMap<>();
        map.put("worldName", entry.worldName());
        map.put("displayName", entry.displayName());
        map.put("ownerUuid", entry.ownerUuid());
        map.put("ownerName", entry.ownerName());
        map.put("serverName", entry.serverName());
        map.put("invitedPlayers", copyNames(entry.invitedPlayers()));
        map.put("trustedPlayers", copyNames(entry.trustedPlayers()));
        map.put("public", entry.isPublic());
        map.put("archived", entry.isArchived());
        map.put("spawnX", entry.spawnX());
        map.put("spawnY", entry.spawnY());
        map.put("spawnZ", entry.spawnZ());
        return Collections.unmodifiableMap(map);
    }

    private static List<String> copyNames(List<String> names) {
        if (names == null) {
            return List.of();
        }
        return names.stream().filter(name -> name != null && !name.isBlank()).toList();
    }
}
