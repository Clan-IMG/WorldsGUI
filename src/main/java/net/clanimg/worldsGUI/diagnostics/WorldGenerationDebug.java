package net.clanimg.worldsGUI.diagnostics;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Temporary observation only: no creator mutation, save, chunk access or lifecycle changes. */
public final class WorldGenerationDebug implements Listener {
    private static final String PREFIX = "[WorldGenDebug] ";
    private final JavaPlugin plugin;
    private final AtomicLong sequence = new AtomicLong();
    private final ThreadLocal<String> operation = new ThreadLocal<>();

    public WorldGenerationDebug(JavaPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        observe(() -> {
            if (enabled()) {
                log("START revision=create-trace-v1 plugin=" + plugin.getDescription().getFullName()
                    + " server=" + Bukkit.getVersion() + " bukkit=" + Bukkit.getBukkitVersion()
                    + " worldContainer=" + Bukkit.getWorldContainer().getAbsolutePath()
                    + " artifact=" + artifact());
            }
        });
    }

    public void request(String source, String worldName, String requestedType, String targetServer,
                        String localServer, boolean local) {
        observe(() -> {
            if (matches(worldName)) {
                log("REQUEST source=" + source + " world=" + worldName + " requestedType=" + requestedType
                    + " targetServer=" + targetServer + " localServer=" + localServer + " local=" + local);
            }
        });
    }

    public World createWorld(String source, WorldCreator creator) {
        if (!matches(creator.name())) {
            return Bukkit.createWorld(creator);
        }
        String id = "create-" + sequence.incrementAndGet();
        String parent = operation.get();
        operation.set(id + "/" + source);
        observe(() -> {
            log("CREATE_BEFORE id=" + id + " source=" + source + " parent=" + parent
                + " name=" + creator.name() + " key=" + creator.key()
                + " environment=" + creator.environment() + " type=" + creator.type()
                + " generator=" + className(creator.generator())
                + " generatorSettings=" + creator.generatorSettings()
                + " biomeProvider=" + className(creator.biomeProvider()) + " seed=" + creator.seed()
                + " generateStructures=" + creator.generateStructures()
                + " loadedByName=" + (Bukkit.getWorld(creator.name()) != null)
                + " loadedByKey=" + (Bukkit.getWorld(creator.key()) != null));
            snapshot(id + "/BEFORE", creator.name());
        });
        try {
            // The exact original call, with the exact original creator.
            World world = Bukkit.createWorld(creator);
            observe(() -> {
                log("CREATE_AFTER id=" + id + " source=" + source + " returned=" + (world != null));
                snapshot(id + "/AFTER", creator.name());
                if (world != null) {
                    sampleLater(id, world);
                }
            });
            return world;
        } catch (RuntimeException | Error ex) {
            observe(() -> log("CREATE_THROW id=" + id + " exception=" + ex.getClass().getName()
                + " message=" + ex.getMessage()));
            throw ex;
        } finally {
            restoreOperation(parent);
        }
    }

    public boolean dispatchCommand(String source, CommandSender sender, String command) {
        String[] parts = command.trim().split("\\s+");
        boolean multiverse = isMultiverse(parts);
        String worldName = multiverse && parts.length >= 3 ? parts[2] : null;
        if (!isTracedCommand(parts) || !matches(worldName)) {
            return Bukkit.dispatchCommand(sender, command);
        }
        String id = "command-" + sequence.incrementAndGet();
        String parent = operation.get();
        operation.set(id + "/" + source);
        observe(() -> {
            log("COMMAND_BEFORE id=" + id + " source=" + source + " parent=" + parent
                + " sender=" + sender.getName() + " command=" + command);
            if (worldName != null) {
                snapshot(id + "/BEFORE", worldName);
            }
        });
        try {
            boolean handled = Bukkit.dispatchCommand(sender, command);
            observe(() -> {
                log("COMMAND_AFTER id=" + id + " dispatchReturned=" + handled);
                if (worldName != null) {
                    snapshot(id + "/AFTER", worldName);
                }
            });
            return handled;
        } catch (RuntimeException | Error ex) {
            observe(() -> log("COMMAND_THROW id=" + id + " exception=" + ex.getClass().getName()
                + " message=" + ex.getMessage()));
            throw ex;
        } finally {
            restoreOperation(parent);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInit(WorldInitEvent event) {
        lifecycle("WORLD_INIT", event.getWorld(), "");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(WorldLoadEvent event) {
        lifecycle("WORLD_LOAD", event.getWorld(), "");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSave(WorldSaveEvent event) {
        World world = event.getWorld();
        observe(() -> {
            if (matches(world.getName())) {
                lifecycle("WORLD_SAVE_EVENT", world, "");
                // The event does not prove that level.dat has already been written.
                Bukkit.getScheduler().runTaskLater(plugin, () -> observe(() -> {
                    if (matches(world.getName())) {
                        snapshot("AFTER_SAVE_EVENT", world.getName());
                    }
                }), 1L);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnload(WorldUnloadEvent event) {
        lifecycle("WORLD_UNLOAD_EVENT", event.getWorld(), " cancelled=" + event.isCancelled());
    }

    private void lifecycle(String phase, World world, String extra) {
        observe(() -> {
            if (!matches(world.getName())) {
                return;
            }
            log(phase + " operation=" + operation.get() + " world=" + world.getName() + extra
                + " runtime=" + runtime(world) + " caller=" + caller());
        });
    }

    private void sampleLater(String id, World expected) {
        for (long ticks : new long[] {1L, 20L, 100L}) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> observe(() -> {
                if (matches(expected.getName())) {
                    World current = Bukkit.getWorld(expected.getName());
                    log("SAMPLE id=" + id + " ticksLater=" + ticks + " sameWorldObject=" + (current == expected));
                    snapshot(id + "/TICK+" + ticks, expected.getName());
                }
            }), ticks);
        }
    }

    private void snapshot(String label, String name) {
        World world = Bukkit.getWorld(name);
        Path folder = (world == null ? Bukkit.getWorldContainer().toPath().resolve(name)
            : world.getWorldFolder().toPath()).toAbsolutePath().normalize();
        Path levelDat = folder.resolve("level.dat");
        String nbt;
        try {
            nbt = LevelDatGenerationProbe.describe(levelDat);
        } catch (Exception ex) {
            nbt = "unreadable(" + ex.getClass().getSimpleName() + ":" + ex.getMessage() + ")";
        }
        log("STATE label=" + label + " lookupName=" + name + " folder=" + folder
            + " folderExists=" + Files.isDirectory(folder) + " levelDatExists=" + Files.isRegularFile(levelDat)
            + " levelDatBytes=" + levelDat.toFile().length()
            + " levelDatModifiedMillis=" + levelDat.toFile().lastModified()
            + " levelDatOldExists=" + Files.isRegularFile(folder.resolve("level.dat_old"))
            + " runtime=" + (world == null ? "unloaded" : runtime(world)) + " nbt={" + nbt + "}");
    }

    @SuppressWarnings("deprecation")
    private String runtime(World world) {
        return "{name=" + world.getName() + ",key=" + world.getKey() + ",uuid=" + world.getUID()
            + ",object=" + Integer.toHexString(System.identityHashCode(world))
            + ",environment=" + world.getEnvironment() + ",type=" + world.getWorldType()
            + ",customGenerator=" + className(world.getGenerator())
            + ",biomeProvider=" + className(world.getBiomeProvider()) + ",seed=" + world.getSeed()
            + ",generateStructures=" + world.canGenerateStructures()
            + ",populators=" + world.getPopulators().stream().map(WorldGenerationDebug::className).toList() + "}";
    }

    private String artifact() throws Exception {
        Path path = Path.of(WorldGenerationDebug.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(path)) {
            return path + "(classes-directory)";
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) != -1;) {
                digest.update(buffer, 0, read);
            }
        }
        return path + " sha256=" + HexFormat.of().formatHex(digest.digest());
    }

    private String caller() {
        return Arrays.stream(Thread.currentThread().getStackTrace())
            .filter(frame -> !frame.getClassName().startsWith("java.")
                && !frame.getClassName().startsWith("jdk.")
                && !frame.getClassName().startsWith("net.clanimg.worldsGUI.diagnostics."))
            .limit(12).map(StackTraceElement::toString).toList().toString();
    }

    private static String className(Object object) {
        return object == null ? "null" : object.getClass().getName();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("debug.world-generation", false);
    }

    private boolean matches(String worldName) {
        if (!enabled()) {
            return false;
        }
        List<String> worlds = plugin.getConfig().getStringList("debug.world-generation-worlds");
        return worldName == null || worlds.isEmpty() || worlds.stream().anyMatch(worldName::equalsIgnoreCase);
    }

    private static boolean isMultiverse(String[] parts) {
        return parts.length >= 2 && (parts[0].equalsIgnoreCase("mv")
            || parts[0].toLowerCase(Locale.ROOT).endsWith(":mv") || parts[0].equalsIgnoreCase("multiverse"));
    }

    private static boolean isTracedCommand(String[] parts) {
        if (isMultiverse(parts)) {
            return List.of("create", "import", "load", "unload", "clone", "regen", "remove", "delete")
                .contains(parts[1].toLowerCase(Locale.ROOT));
        }
        if (parts.length >= 2 && (parts[0].equalsIgnoreCase("nav")
            || parts[0].toLowerCase(Locale.ROOT).endsWith(":nav"))) {
            return parts[1].equalsIgnoreCase("create") || (parts.length >= 3
                && (parts[1].equalsIgnoreCase("my-world") || parts[1].equalsIgnoreCase("my-worlds"))
                && parts[2].equalsIgnoreCase("create"));
        }
        return false;
    }

    private void restoreOperation(String parent) {
        if (parent == null) {
            operation.remove();
        } else {
            operation.set(parent);
        }
    }

    private void log(String message) {
        plugin.getLogger().info(PREFIX + message.replace('\n', ' ').replace('\r', ' ')
            + " thread=" + Thread.currentThread().getName() + " mainThread=" + Bukkit.isPrimaryThread());
    }

    private void observe(Observation action) {
        try {
            action.run();
        } catch (Exception | LinkageError ex) {
            plugin.getLogger().warning(PREFIX + "Diagnostic observation failed: " + ex);
        }
    }

    @FunctionalInterface
    private interface Observation {
        void run() throws Exception;
    }
}
