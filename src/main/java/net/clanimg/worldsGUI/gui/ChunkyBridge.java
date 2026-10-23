package net.clanimg.worldsGUI.gui;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Steuert die Chunky-Vorgenerierung einzelner Welten über die Chunky-API (org.popcraft.chunky.api.ChunkyAPI).
 * Die API wird per Reflection angesprochen, damit WorldsGUI ohne Chunky läuft und keine Build-Abhängigkeit braucht.
 *
 * Schutz gegen Spam: pro Welt darf nur alle {@code chunky.toggle-cooldown-seconds} umgeschaltet werden, und es laufen
 * höchstens {@code chunky.max-parallel-tasks} Vorgenerierungen gleichzeitig - jeder Start lastet CPU und Festplatte aus.
 */
public final class ChunkyBridge {
    public enum ToggleResult { STARTED, STOPPED, ALREADY_RUNNING, NOT_RUNNING, COOLDOWN, TOO_MANY_TASKS, WORLD_NOT_LOADED, UNAVAILABLE, FAILED }

    private static final String API_CLASS = "org.popcraft.chunky.api.ChunkyAPI";

    private final JavaPlugin plugin;
    private final Map<String, Long> lastToggleMillis = new ConcurrentHashMap<>();
    private final Map<String, Float> progressByWorld = new ConcurrentHashMap<>();
    private Object api;
    private boolean listenersRegistered;

    public ChunkyBridge(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isAvailable() {
        return resolveApi() != null;
    }

    public boolean isRunning(String worldName) {
        Object chunky = resolveApi();
        if (chunky == null || worldName == null) {
            return false;
        }
        return Boolean.TRUE.equals(invoke(chunky, "isRunning", new Class<?>[] {String.class}, worldName));
    }

    /** Fortschritt der laufenden Vorgenerierung in Prozent (0-100), oder -1, wenn unbekannt. */
    public float progress(String worldName) {
        return worldName == null ? -1f : progressByWorld.getOrDefault(worldName.toLowerCase(Locale.ROOT), -1f);
    }

    public int radius() {
        return Math.max(16, plugin.getConfig().getInt("chunky.radius", 2500));
    }

    /** Startet die Vorgenerierung ohne Cooldown-/Limit-Prüfung (z.B. direkt nach dem Erstellen einer Welt). */
    public boolean start(World world) {
        Object chunky = resolveApi();
        if (chunky == null || world == null) {
            return false;
        }
        Location spawn = world.getSpawnLocation();
        double radius = radius();
        Object started = invoke(chunky, "startTask",
            new Class<?>[] {String.class, String.class, double.class, double.class, double.class, double.class, String.class},
            world.getName(), "circle", (double) spawn.getBlockX(), (double) spawn.getBlockZ(), radius, radius, "region");
        if (Boolean.TRUE.equals(started)) {
            progressByWorld.put(world.getName().toLowerCase(Locale.ROOT), 0f);
            return true;
        }
        return false;
    }

    /**
     * Schaltet die Vorgenerierung einer Welt auf Wunsch eines Spielers an oder aus, mit Cooldown und Parallel-Limit.
     */
    public ToggleResult toggle(String worldName, boolean enable, boolean bypassLimits) {
        if (resolveApi() == null) {
            return ToggleResult.UNAVAILABLE;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return ToggleResult.WORLD_NOT_LOADED;
        }
        String key = world.getName().toLowerCase(Locale.ROOT);
        boolean running = isRunning(world.getName());
        if (enable && running) {
            return ToggleResult.ALREADY_RUNNING;
        }
        if (!enable && !running) {
            return ToggleResult.NOT_RUNNING;
        }
        if (!bypassLimits && cooldownRemainingSeconds(world.getName()) > 0) {
            return ToggleResult.COOLDOWN;
        }
        if (enable && !bypassLimits && runningTaskCount() >= maxParallelTasks()) {
            return ToggleResult.TOO_MANY_TASKS;
        }

        boolean ok = enable
            ? start(world)
            : Boolean.TRUE.equals(invoke(resolveApi(), "cancelTask", new Class<?>[] {String.class}, world.getName()));
        if (!ok) {
            return ToggleResult.FAILED;
        }
        lastToggleMillis.put(key, System.currentTimeMillis());
        if (!enable) {
            progressByWorld.remove(key);
        }
        return enable ? ToggleResult.STARTED : ToggleResult.STOPPED;
    }

    public long cooldownRemainingSeconds(String worldName) {
        Long last = lastToggleMillis.get(worldName.toLowerCase(Locale.ROOT));
        if (last == null) {
            return 0;
        }
        long cooldownMillis = Math.max(0, plugin.getConfig().getInt("chunky.toggle-cooldown-seconds", 60)) * 1000L;
        long remaining = last + cooldownMillis - System.currentTimeMillis();
        return remaining <= 0 ? 0 : (remaining + 999) / 1000;
    }

    public int maxParallelTasks() {
        return Math.max(1, plugin.getConfig().getInt("chunky.max-parallel-tasks", 2));
    }

    public int runningTaskCount() {
        int count = 0;
        for (World world : Bukkit.getWorlds()) {
            if (isRunning(world.getName())) {
                count++;
            }
        }
        return count;
    }

    private Object resolveApi() {
        if (api != null) {
            return api;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("Chunky")) {
            return null;
        }
        try {
            Class<?> apiClass = Class.forName(API_CLASS, true, Bukkit.getPluginManager().getPlugin("Chunky").getClass().getClassLoader());
            api = Bukkit.getServicesManager().load(apiClass);
            if (api != null) {
                registerListeners(apiClass);
            }
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("Chunky-API konnte nicht geladen werden: " + ex.getMessage());
        }
        return api;
    }

    private void registerListeners(Class<?> apiClass) {
        if (listenersRegistered) {
            return;
        }
        listenersRegistered = true;
        try {
            Method onProgress = apiClass.getMethod("onGenerationProgress", Consumer.class);
            Consumer<Object> progressListener = event -> {
                Object world = invoke(event, "world", new Class<?>[0]);
                Object progress = invoke(event, "progress", new Class<?>[0]);
                Object complete = invoke(event, "complete", new Class<?>[0]);
                if (world instanceof String name && progress instanceof Float value) {
                    if (Boolean.TRUE.equals(complete)) {
                        progressByWorld.remove(name.toLowerCase(Locale.ROOT));
                    } else {
                        progressByWorld.put(name.toLowerCase(Locale.ROOT), value);
                    }
                }
            };
            onProgress.invoke(api, progressListener);
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Chunky-Fortschritt kann nicht angezeigt werden: " + ex.getMessage());
        }
    }

    private Object invoke(Object target, String method, Class<?>[] types, Object... args) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(method, types);
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Chunky-API-Aufruf '" + method + "' fehlgeschlagen: " + ex.getMessage());
            return null;
        }
    }
}
