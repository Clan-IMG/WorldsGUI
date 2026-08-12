package net.clanimg.worldsGUI.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.RegionGroup;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.storage.StorageException;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.clanimg.worldsGUI.Permissions;
import net.clanimg.worldsGUI.WorldsGUI;
import net.clanimg.worldsGUI.data.WorldsRepository;
import net.clanimg.worldsGUI.guiconfig.GuiAction;
import net.clanimg.worldsGUI.guiconfig.GuiConfig;
import net.clanimg.worldsGUI.guiconfig.GuiDefinition;
import net.clanimg.worldsGUI.guiconfig.GuiSlotDefinition;
import net.clanimg.worldsGUI.guiconfig.GuiSlotRangeDefinition;
import net.clanimg.worldsGUI.guiconfig.GuiToggleState;
import net.clanimg.worldsGUI.guiconfig.GuiTrigger;
import net.clanimg.worldsGUI.guiconfig.TriggerType;
import net.clanimg.worldsGUI.guiconfig.SlotMapper;
import net.clanimg.worldsGUI.guiruntime.PlaceholderExpander;
import net.clanimg.worldsGUI.guiruntime.PlayerGuiSession;
import net.clanimg.worldsGUI.guiruntime.PlayerSessionManager;
import net.clanimg.worldsGUI.guiruntime.ToggleRuntime;
import net.clanimg.worldsGUI.guiruntime.TriggerDispatcher;
import net.clanimg.worldsGUI.model.WorldEntry;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.GameMode;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

public final class GuiManager {
    private static final int MAIN_SIZE = 54;
    private static final int CREATE_OPTIONS_SIZE = 27;
    private static final int PAGE_SIZE = 36;
    private static final int WORLD_BORDER_BLOCKS = 29_999_984;
    private static final java.util.Set<String> RUNTIME_GUI_IDS = java.util.Set.of(
        "confirm",
        "create-world",
        "select-server",
        "my-worlds",
        "edit-world",
        "invited-worlds",
        "invited-friends",
        "trusted-friends",
        "select-friend",
        "edit-friend",
        "tickets",
        "archived-tickets"
    );
    private static final Pattern WORLD_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-]{3,32}$");
    private static final Pattern ORDER_LABEL_PATTERN = Pattern.compile("^A\\d{3}$");
    private static final Pattern GENERATED_WORLD_NAME_PATTERN = Pattern.compile("^.+-(\\d{4})$");
    private static final Pattern LEGACY_CODE_PATTERN = Pattern.compile("(?i)[&§]([0-9A-FK-OR])");
    private static final String FLAT_WORLD_GENERATOR_SETTINGS = "{\"biome\":\"minecraft:plains\",\"features\":false,\"lakes\":false,\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:dirt\",\"height\":64},{\"block\":\"minecraft:grass_block\",\"height\":1}]}";

    private static final Map<Character, String> LEGACY_TO_MINI = Map.ofEntries(
        Map.entry('0', "<black>"),
        Map.entry('1', "<dark_blue>"),
        Map.entry('2', "<dark_green>"),
        Map.entry('3', "<dark_aqua>"),
        Map.entry('4', "<dark_red>"),
        Map.entry('5', "<dark_purple>"),
        Map.entry('6', "<gold>"),
        Map.entry('7', "<gray>"),
        Map.entry('8', "<dark_gray>"),
        Map.entry('9', "<blue>"),
        Map.entry('a', "<green>"),
        Map.entry('b', "<aqua>"),
        Map.entry('c', "<red>"),
        Map.entry('d', "<light_purple>"),
        Map.entry('e', "<yellow>"),
        Map.entry('f', "<white>"),
        Map.entry('k', "<obfuscated>"),
        Map.entry('l', "<bold>"),
        Map.entry('m', "<strikethrough>"),
        Map.entry('n', "<underlined>"),
        Map.entry('o', "<italic>"),
        Map.entry('r', "<reset>")
    );

    private final WorldsGUI plugin;
    private final WorldsRepository repository;
    private final NamespacedKey worldKey;
    private FileConfiguration messagesConfig;
    private FileConfiguration worldDefaultsConfig;

    private final Map<UUID, String> selectedWorldByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, PendingInput> pendingInputs = new ConcurrentHashMap<>();
    private final Map<UUID, PendingDeleteConfirmation> pendingDeleteByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastAppliedLuckPermsState = new ConcurrentHashMap<>();
    private final Map<UUID, GuiTrigger> pendingAnvilInputs = new ConcurrentHashMap<>();
    private final Set<UUID> suppressedChatFeedbackPlayers = ConcurrentHashMap.newKeySet();
    private final Set<String> metadataWorldCreationsInProgress = ConcurrentHashMap.newKeySet();
    private volatile boolean warnedMissingLocalServerId;
    private volatile boolean warnedMissingExplicitServerIdForAutoCreate;
    private volatile boolean warnedBlockedLocalServerForAutoCreate;
    private volatile GuiConfig guiConfig;
    private final PlayerSessionManager playerSessions = new PlayerSessionManager();
    private final TriggerDispatcher triggerDispatcher;

    public GuiManager(WorldsGUI plugin, WorldsRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        this.worldKey = new NamespacedKey(plugin, "world_name");
        this.messagesConfig = loadMessagesConfig();
        this.worldDefaultsConfig = loadWorldDefaultsConfig();
        this.triggerDispatcher = new TriggerDispatcher(
            this::openGui,
            (player, command, chatFeedback) -> {
                UUID playerId = player.getUniqueId();
                if (!chatFeedback) {
                    suppressedChatFeedbackPlayers.add(playerId);
                }
                try {
                    if (!dispatchRuntimeGuiCommand(player, command)) {
                        Bukkit.dispatchCommand(player, command);
                    }
                } finally {
                    if (!chatFeedback) {
                        suppressedChatFeedbackPlayers.remove(playerId);
                    }
                }
            },
            this::requestAnvilInput,
            (player, key, fallbackLiteral, replacements) -> sendNoPrefixConfigured(player, key, fallbackLiteral, replacements),
            guiId -> guiConfig == null ? null : guiConfig.get(guiId).map(GuiDefinition::returnGuiId).orElse(null)
        );
    }

    private FileConfiguration loadWorldDefaultsConfig() {
        File defaultsFile = new File(plugin.getDataFolder(), "world-defaults.yml");
        if (!defaultsFile.exists()) {
            return new YamlConfiguration();
        }
        return YamlConfiguration.loadConfiguration(defaultsFile);
    }

    private FileConfiguration loadMessagesConfig() {
        File messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            return new YamlConfiguration();
        }
        return YamlConfiguration.loadConfiguration(messagesFile);
    }

    private boolean dispatchRuntimeGuiCommand(Player player, String rawCommand) {
        if (rawCommand == null) {
            return false;
        }

        String command = rawCommand.trim();
        if (command.isEmpty()) {
            return false;
        }

        String[] args = command.split("\\s+");
        if (args.length < 5 || !args[0].equalsIgnoreCase("nav")) {
            return false;
        }

        boolean isMyWorldNamespace = args[1].equalsIgnoreCase("my-world") || args[1].equalsIgnoreCase("my-worlds");
        if (!isMyWorldNamespace || !args[2].equalsIgnoreCase("delete") || !args[4].equalsIgnoreCase("confirm")) {
            return false;
        }

        executeNavMyWorldDeleteFromRuntimeGui(player, args[3]);
        return true;
    }

    private void executeNavMyWorldDeleteFromRuntimeGui(Player player, String worldName) {
        if (!hasPermission(player, Permissions.USE, true)) {
            return;
        }
        // GUI-confirmed delete should use server-side flow while still enforcing owner/admin checks.
        deleteWorld(player, worldName);
    }

    public void shutdown() {
        selectedWorldByPlayer.clear();
        pendingInputs.clear();
        pendingDeleteByPlayer.clear();
        lastAppliedLuckPermsState.clear();
        playerSessions.clear();
        pendingAnvilInputs.clear();
    }

    /**
     * Wird beim Plugin-Start nach erfolgreicher Validierung von guis.yml gesetzt.
     * Die eigentliche Runtime-Nutzung (Rendern/Klicks über die neue Engine) folgt in einem späteren Schritt.
     */
    public void setGuiConfig(GuiConfig guiConfig) {
        this.guiConfig = guiConfig;
    }

    public GuiConfig guiConfig() {
        return guiConfig;
    }

    public void reloadWorldDefaultsConfig() {
        this.worldDefaultsConfig = loadWorldDefaultsConfig();
    }

    public void reloadMessagesConfig() {
        this.messagesConfig = loadMessagesConfig();
    }

    /**
     * Zentraler Einstiegspunkt zum Öffnen eines GUIs über eine gui-id (aus guis.yml Triggern
     * wie open-gui/return/select, oder direkt vom Legacy-Code aus). GUIs aus {@link #RUNTIME_GUI_IDS}
     * werden über die neue Runtime-Engine gerendert; alle anderen bekannten IDs fallen (Hybrid-Rollout)
     * vorerst auf die bestehende, hardcodierte Java-GUI-Logik zurück.
     */
    private void openGui(Player player, String guiId) {
        if (guiId == null || guiId.isBlank()) {
            return;
        }
        String normalized = guiId.trim().toLowerCase(Locale.ROOT);
        if (RUNTIME_GUI_IDS.contains(normalized)) {
            openRuntimeGui(player, normalized);
            return;
        }
        plugin.getLogger().warning("GUI '" + guiId + "' wird von der neuen guis.yml-Engine noch nicht unterstützt.");
    }

    private void openRuntimeGui(Player player, String guiId) {
        if (guiConfig == null) {
            send(player, "runtime.gui-config-not-loaded");
            return;
        }

        Optional<GuiDefinition> definitionOpt = guiConfig.get(guiId);
        if (definitionOpt.isEmpty()) {
            send(player, "runtime.gui-not-found", "%gui%", guiId);
            return;
        }

        GuiDefinition definition = definitionOpt.get();
        PlayerGuiSession session = playerSessions.getOrCreate(player.getUniqueId());
        session.setCurrentGuiId(guiId);

        RuntimeGuiHolder holder = new RuntimeGuiHolder(guiId);
        Inventory inventory = Bukkit.createInventory(holder, definition.size(), parseFormattedMessage(definition.title()));

        renderRowSlots(inventory, holder, definition, player, session);
        renderSlotRange(inventory, holder, definition, player, session);
        renderAutoContent(inventory, holder, definition, player);

        player.openInventory(inventory);
    }

    private void renderRowSlots(Inventory inventory, RuntimeGuiHolder holder, GuiDefinition definition, Player player, PlayerGuiSession session) {
        for (int rowSlot = 1; rowSlot <= 9; rowSlot++) {
            int absolute = SlotMapper.mapRowSlotToAbsolute(rowSlot, definition.position(), definition.size());
            GuiSlotDefinition slotDefinition = definition.rowSlots().get(rowSlot);

            if (slotDefinition == null) {
                inventory.setItem(absolute, namedItem(Material.GRAY_STAINED_GLASS_PANE, ""));
                continue;
            }

            String materialTemplate = slotDefinition.material();
            String titleTemplate = slotDefinition.title();

            GuiTrigger anyClickTrigger = slotDefinition.action() == null ? null : slotDefinition.action().anyClickTrigger();
            if (anyClickTrigger != null && anyClickTrigger.type() == TriggerType.TOGGLE) {
                GuiToggleState state = ToggleRuntime.currentState(anyClickTrigger, player, session, Map.of());
                String currentStateId = ToggleRuntime.currentStateId(anyClickTrigger, player, session, Map.of());
                String scopedId = ToggleRuntime.scopedToggleId(anyClickTrigger, player, session, Map.of());

                if (!scopedId.isBlank()) {
                    session.putSelection(scopedId, currentStateId);
                }
                if (anyClickTrigger.toggleId() != null && !anyClickTrigger.toggleId().isBlank()) {
                    session.putSelection(anyClickTrigger.toggleId(), currentStateId);
                }

                if (state != null) {
                    materialTemplate = state.material();
                    titleTemplate = state.title();
                }
            }

            String title = PlaceholderExpander.expand(titleTemplate, player, session, Map.of());
            inventory.setItem(absolute, buildRuntimeItem(materialTemplate, title == null ? "" : title, player, session, Map.of()));

            if (slotDefinition.action() != null) {
                holder.putClickHandler(absolute, slotDefinition.action(), Map.of());
            }
        }
    }

    private void renderSlotRange(Inventory inventory, RuntimeGuiHolder holder, GuiDefinition definition, Player player, PlayerGuiSession session) {
        GuiSlotRangeDefinition range = definition.slotRange();
        if (range == null) {
            return;
        }

        List<Map<String, String>> items = resolveSlotRangeItems(range, player, session);
        int slotCount = range.to() - range.from() + 1;
        for (int i = 0; i < slotCount && i < items.size(); i++) {
            int absolute = range.from() + i;
            Map<String, String> extra = items.get(i);
            String title = PlaceholderExpander.expand(range.title(), player, session, extra);
            inventory.setItem(absolute, buildRuntimeItem(range.material(), title == null ? "" : title, player, session, extra));

            if (range.action() != null) {
                holder.putClickHandler(absolute, range.action(), extra);
            }
        }
    }

    private void renderAutoContent(Inventory inventory, RuntimeGuiHolder holder, GuiDefinition definition, Player player) {
        ViewMode mode = switch (holder.guiId()) {
            case "my-worlds" -> ViewMode.OWN;
            case "invited-worlds" -> ViewMode.INVITED;
            case "archived-tickets" -> ViewMode.ARCHIVED;
            case "tickets" -> ViewMode.PUBLIC;
            default -> null;
        };
        if (mode == null && !"own-worlds".equalsIgnoreCase(definition.autoContentSource())) {
            return;
        }

        List<WorldEntry> worlds = listWorldsFor(player, mode == null ? ViewMode.OWN : mode);
        int worldIndex = 0;

        for (int absoluteSlot = 0; absoluteSlot < definition.size(); absoluteSlot++) {
            if (inventory.getItem(absoluteSlot) != null) {
                continue;
            }
            if (worldIndex >= worlds.size()) {
                break;
            }

            WorldEntry entry = worlds.get(worldIndex++);
            inventory.setItem(absoluteSlot, worldIcon(entry, mode == null ? ViewMode.OWN : mode, player.getWorld().getName()));
        }
    }

    /**
     * Liefert die dynamischen Werte je generiertem Slot-Range-Item (z.B. server_id/server_name).
     * trusted-players/luckperms-players werden in einem späteren Schritt angebunden.
     */
    private List<Map<String, String>> resolveSlotRangeItems(GuiSlotRangeDefinition range, Player player, PlayerGuiSession session) {
        String source = range.source();
        String filter = PlaceholderExpander.expand(range.filter(), player, session, Map.of());
        if (source == null || source.isBlank()) {
            return List.of();
        }

        if ("servers".equalsIgnoreCase(source)) {
            List<Map<String, String>> items = new ArrayList<>();
            for (String serverName : resolveSelectableServerNames()) {
                items.add(Map.of("server_id", serverName, "server_name", serverName));
            }
            return applySlotRangeFilter(items, filter);
        }

        if ("invited-players".equalsIgnoreCase(source) || "trusted-players".equalsIgnoreCase(source)) {
            String worldName = resolveContextWorld(player);
            if (worldName == null || worldName.isBlank()) {
                return List.of();
            }

            Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
            if (entryOpt.isEmpty()) {
                return List.of();
            }

            List<String> values = "invited-players".equalsIgnoreCase(source)
                ? entryOpt.get().invitedPlayers()
                : entryOpt.get().trustedPlayers();
            return toPlayerSlotItems(values, filter);
        }

        if ("luckperms-players".equalsIgnoreCase(source)) {
            List<String> players = resolveRegisteredLuckPermsPlayers();
            if (players.isEmpty()) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    players.add(online.getName());
                }
                for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
                    if (offline.getName() != null && !offline.getName().isBlank()) {
                        players.add(offline.getName());
                    }
                }
            }
            return toPlayerSlotItems(players, filter, player.getName());
        }
        return List.of();
    }

    private List<Map<String, String>> toPlayerSlotItems(List<String> players, String filter) {
        return toPlayerSlotItems(players, filter, null);
    }

    private List<Map<String, String>> toPlayerSlotItems(List<String> players, String filter, String excludePlayerName) {
        List<Map<String, String>> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String normalizedFilter = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        String excluded = excludePlayerName == null ? "" : excludePlayerName.trim().toLowerCase(Locale.ROOT);

        for (String playerName : players) {
            if (playerName == null) {
                continue;
            }

            String normalized = playerName.trim();
            if (normalized.isBlank()) {
                continue;
            }

            // Blendet unerwünschte Dummy-Einträge aus, die in manchen Setups in der Liste landen.
            if ("dynamic".equalsIgnoreCase(normalized)) {
                continue;
            }

            if (!excluded.isBlank() && normalized.equalsIgnoreCase(excludePlayerName)) {
                continue;
            }

            if (!normalizedFilter.isBlank() && !normalized.toLowerCase(Locale.ROOT).contains(normalizedFilter)) {
                continue;
            }

            if (!seen.add(normalized.toLowerCase(Locale.ROOT))) {
                continue;
            }

            items.add(Map.of("target_player_name", normalized));
        }

        items.sort(Comparator.comparing(item -> item.get("target_player_name"), String.CASE_INSENSITIVE_ORDER));
        return items;
    }

    private List<Map<String, String>> applySlotRangeFilter(List<Map<String, String>> items, String filter) {
        if (filter == null || filter.isBlank()) {
            return items;
        }

        String normalizedFilter = filter.trim().toLowerCase(Locale.ROOT);
        List<Map<String, String>> filtered = new ArrayList<>();
        for (Map<String, String> item : items) {
            String haystack = String.join(" ", item.values()).toLowerCase(Locale.ROOT);
            if (haystack.contains(normalizedFilter)) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    private Material resolveMaterial(String materialName) {
        if (materialName == null || materialName.isBlank()) {
            return Material.GRAY_STAINED_GLASS_PANE;
        }
        Material material = Material.matchMaterial(materialName.trim());
        if (material == null) {
            plugin.getLogger().warning("Unbekanntes Material in guis.yml: '" + materialName + "', nutze STONE als Fallback.");
            return Material.STONE;
        }
        return material;
    }

    private ItemStack buildRuntimeItem(String materialTemplate, String title, Player player, PlayerGuiSession session, Map<String, String> extra) {
        String token = materialTemplate == null ? "" : materialTemplate.trim();
        if ("%player_head%".equalsIgnoreCase(token)) {
            // %player_head% ist immer der Kopf des Spielers, der das GUI gerade geöffnet hat.
            return buildPlayerHeadItem(title, player, player == null ? null : player.getName());
        }
        if ("%target_player_head%".equalsIgnoreCase(token)) {
            // %target_player_head% ist der Kopf des Spielers aus der dynamisch generierten Liste
            // (z.B. eingeladene/getrustete Spieler), NICHT der eigene Kopf des Betrachters.
            String targetName = extra == null ? null : extra.get("target_player_name");
            if (targetName == null || targetName.isBlank()) {
                targetName = player == null ? null : player.getName();
            }
            return buildPlayerHeadItem(title, player, targetName);
        }

        Material material = resolveMaterial(materialTemplate);
        return namedItem(material, title);
    }

    private ItemStack buildPlayerHeadItem(String title, Player viewer, String targetName) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof SkullMeta skullMeta)) {
            return namedItem(Material.PLAYER_HEAD, title);
        }

        if (targetName != null && !targetName.isBlank()) {
            if (viewer != null && viewer.getName().equalsIgnoreCase(targetName)) {
                // Der Online-Spieler hat sein Profil (inkl. Skin-Textur) bereits geladen -
                // Bukkit.getOfflinePlayer(...) liefert hier sonst oft nur den Default-Kopf.
                skullMeta.setOwningPlayer(viewer);
            } else {
                OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(targetName);
                skullMeta.setOwningPlayer(cached != null ? cached : Bukkit.getOfflinePlayer(targetName));
            }
        }

        skullMeta.displayName(legacy(title));
        item.setItemMeta(skullMeta);
        return item;
    }

    private void handleRuntimeGuiClick(Player player, InventoryClickEvent event, RuntimeGuiHolder holder) {
        int slot = event.getRawSlot();
        Inventory top = event.getView().getTopInventory();
        if (slot < 0 || slot >= top.getSize()) {
            return;
        }

        RuntimeGuiHolder.ClickHandler clickHandler = holder.clickHandler(slot);
        if (clickHandler == null || clickHandler.action() == null) {
            handleRuntimeWorldItemClick(player, event, holder);
            return;
        }

        GuiTrigger trigger = clickHandler.action().resolve(event.isLeftClick(), event.isRightClick());
        if (trigger == null) {
            return;
        }

        PlayerGuiSession session = playerSessions.getOrCreate(player.getUniqueId());
        triggerDispatcher.execute(player, session, trigger, clickHandler.extra(), holder.guiId() + ":" + slot);
    }

    private void handleRuntimeWorldItemClick(Player player, InventoryClickEvent event, RuntimeGuiHolder holder) {
        ViewMode mode = switch (holder.guiId()) {
            case "my-worlds" -> ViewMode.OWN;
            case "invited-worlds" -> ViewMode.INVITED;
            case "tickets" -> ViewMode.PUBLIC;
            case "archived-tickets" -> ViewMode.ARCHIVED;
            default -> null;
        };
        if (mode == null) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR || !clicked.hasItemMeta()) {
            return;
        }

        ItemMeta meta = clicked.getItemMeta();
        String worldName = meta.getPersistentDataContainer().get(worldKey, PersistentDataType.STRING);
        if (worldName == null || worldName.isBlank()) {
            return;
        }

        if (event.isLeftClick()) {
            joinWorld(player, worldName, true);
            return;
        }

        if (event.isRightClick() && mode == ViewMode.OWN) {
            Optional<WorldEntry> entry = repository.findByWorldName(worldName);
            if (entry.isPresent() && entry.get().ownerUuid().equals(player.getUniqueId().toString())) {
                selectedWorldByPlayer.put(player.getUniqueId(), worldName);
                PlayerGuiSession session = playerSessions.getOrCreate(player.getUniqueId());
                session.setCurrentWorld(worldName);
                session.pushCurrentToHistory();
                openGui(player, "edit-world");
            }
        }
    }

    public void executeNav(Player player) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV, true)) {
            return;
        }
        openGui(player, "my-worlds");
        syncAssignedTicketWorlds(player);
    }

    /**
     * Holt registrierte User aus LuckPerms (falls verfügbar) ohne harte Compile-Abhängigkeit.
     * Fällt auf eine leere Liste zurück, wenn LuckPerms fehlt oder die API nicht erreichbar ist.
     */
    private List<String> resolveRegisteredLuckPermsPlayers() {
        List<String> names = new ArrayList<>();
        try {
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object api = providerClass.getMethod("get").invoke(null);
            if (api == null) {
                return names;
            }

            Object userManager = api.getClass().getMethod("getUserManager").invoke(api);
            if (userManager == null) {
                return names;
            }

            Object uniqueUsersObj = userManager.getClass().getMethod("getUniqueUsers").invoke(userManager);
            if (!(uniqueUsersObj instanceof Iterable<?> uniqueUsers)) {
                return names;
            }

            Method lookupUsernameMethod = null;
            Method getUserMethod = null;
            Method userGetUsernameMethod = null;
            try {
                lookupUsernameMethod = userManager.getClass().getMethod("lookupUsername", UUID.class);
            } catch (NoSuchMethodException ignored) {
                // Ältere/abweichende API-Variante: dann nur Bukkit-Namenauflösung.
            }
            try {
                getUserMethod = userManager.getClass().getMethod("getUser", UUID.class);
            } catch (NoSuchMethodException ignored) {
                // Optional.
            }

            for (Object entry : uniqueUsers) {
                if (!(entry instanceof UUID uuid)) {
                    continue;
                }

                String name = null;
                if (getUserMethod != null) {
                    Object userObj = getUserMethod.invoke(userManager, uuid);
                    if (userObj != null) {
                        if (userGetUsernameMethod == null) {
                            try {
                                userGetUsernameMethod = userObj.getClass().getMethod("getUsername");
                            } catch (NoSuchMethodException ignored) {
                                // Dann bleibt nur lookupUsername/Bukkit-Fallback.
                            }
                        }
                        if (userGetUsernameMethod != null) {
                            Object loadedName = userGetUsernameMethod.invoke(userObj);
                            if (loadedName instanceof String s && !s.isBlank()) {
                                name = s;
                            }
                        }
                    }
                }

                if (lookupUsernameMethod != null) {
                    Object futureObj = lookupUsernameMethod.invoke(userManager, uuid);
                    if (futureObj instanceof CompletableFuture<?> future) {
                        Object resolved = future.getNow(null);
                        if (!(resolved instanceof String)) {
                            try {
                                // Kurzer, begrenzter Wait für Cache/Storage-Antwort ohne langen Main-Thread-Block.
                                resolved = future.get(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                            } catch (Exception ignored) {
                                resolved = null;
                            }
                        }
                        if (resolved instanceof String s && !s.isBlank()) {
                            name = s;
                        }
                    }
                }

                if (name == null || name.isBlank()) {
                    OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
                    if (offline != null && offline.getName() != null && !offline.getName().isBlank()) {
                        name = offline.getName();
                    }
                }

                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
        } catch (Throwable ignored) {
            // LuckPerms nicht vorhanden oder API nicht verfügbar -> Fallback erfolgt beim Aufrufer.
        }
        return names;
    }

    public void executeSetSpawn(Player player) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.SETSPAWN, true)) {
            return;
        }
        handleSetSpawn(player);
    }

    public void executeNavTicketCreate(Player player, String orderLabel) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_CREATE, true)) {
            return;
        }

        String normalizedOrder = orderLabel == null ? "" : orderLabel.trim().toUpperCase(Locale.ROOT);
        if (!ORDER_LABEL_PATTERN.matcher(normalizedOrder).matches()) {
            send(player, "order.invalid-format");
            return;
        }

        WorldsRepository.OrderAssignmentCheck orderCheck = repository.checkOrderAssignment(normalizedOrder, player.getName());
        if (!orderCheck.exists()) {
            send(player, "order.not-found", "%order%", normalizedOrder);
            return;
        }

        if (!player.hasPermission(Permissions.ADMIN) && !orderCheck.assigned()) {
            send(player, "order.not-assigned");
            return;
        }

        List<String> customers = repository.getOrderSummary(normalizedOrder)
            .map(summary -> summary.customerName() == null || summary.customerName().isBlank()
                ? List.<String>of()
                : List.of(summary.customerName().trim()))
            .orElse(List.of());

        createWorld(player, normalizedOrder, normalizedOrder, false, "ticket", normalizedOrder, customers);
    }

    public void syncAssignedTicketWorlds(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!hasPermission(player, Permissions.USE, false) || !hasPermission(player, Permissions.NAV_CREATE, false)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        String playerName = player.getName();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> orderIds = repository.listAssignedOpenOrderIds(playerName);
            List<PendingTicketWorld> worldsToCreate = new ArrayList<>();

            for (String orderId : orderIds) {
                List<String> customers = repository.getOrderSummary(orderId)
                    .map(summary -> summary.customerName() == null || summary.customerName().isBlank()
                        ? List.<String>of()
                        : List.of(summary.customerName().trim()))
                    .orElse(List.of());
                boolean archived = repository.findByWorldName(orderId)
                    .map(WorldEntry::isArchived)
                    .orElse(false);
                worldsToCreate.add(new PendingTicketWorld(orderId, customers, archived));
            }

            if (worldsToCreate.isEmpty()) {
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player onlinePlayer = Bukkit.getPlayer(playerId);
                if (onlinePlayer == null || !onlinePlayer.isOnline()) {
                    return;
                }

                for (PendingTicketWorld world : worldsToCreate) {
                    if (Bukkit.getWorld(world.worldName()) == null) {
                        createWorld(
                            onlinePlayer,
                            world.worldName(),
                            world.worldName(),
                            false,
                            "ticket",
                            world.worldName(),
                            world.customers()
                        );
                    } else if (world.archived()) {
                        repository.unarchiveWorld(world.worldName());
                    }
                }
            });
        });
    }

    public void syncOpenTicketWorlds() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, WorldEntry> worldsByName = new LinkedHashMap<>();
            for (WorldEntry entry : repository.listOpenTicketWorlds()) {
                worldsByName.put(entry.worldName().toLowerCase(Locale.ROOT), entry);
            }

            String localServerId = resolveLocalServerId();
            if (localServerId != null && !localServerId.isBlank()) {
                for (WorldEntry entry : repository.listServerWorlds(localServerId)) {
                    worldsByName.put(entry.worldName().toLowerCase(Locale.ROOT), entry);
                }
            }

            if (worldsByName.isEmpty()) {
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                for (WorldEntry entry : worldsByName.values()) {
                    if (!isWorldOnThisServer(entry)) {
                        continue;
                    }
                    if (Bukkit.getWorld(entry.worldName()) != null) {
                        continue;
                    }
                    createWorldFromMetadata(entry);
                }
            });
        });
    }

    private void createWorldFromMetadata(WorldEntry entry) {
        String worldName = entry.worldName();
        String worldKey = worldName.toLowerCase(Locale.ROOT);
        String ownerUuid = entry.ownerUuid();
        String ownerName = entry.ownerName();
        List<String> customers = entry.customers() == null || entry.customers().isEmpty()
            ? repository.getOrderSummary(entry.ticketOrderId())
                .map(summary -> summary.customerName() == null || summary.customerName().isBlank()
                    ? List.<String>of()
                    : List.of(summary.customerName().trim()))
                .orElse(List.of())
            : entry.customers();
        int worldIndex = repository.nextWorldIndex(ownerUuid);

        WorldCreator creator = buildWorldCreator(worldName, false);

        World created = Bukkit.createWorld(creator);
        if (created == null) {
            metadataWorldCreationsInProgress.remove(worldKey);
            plugin.getLogger().warning("Ticket-Welt konnte nicht erstellt werden: " + worldName);
            return;
        }

        ConsoleCommandSender console = Bukkit.getConsoleSender();
        Bukkit.dispatchCommand(console, "mv import " + worldName + " normal");

        new BukkitRunnable() {
            @Override
            public void run() {
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    metadataWorldCreationsInProgress.remove(worldKey);
                    plugin.getLogger().warning("Ticket-Welt konnte nach dem Import nicht geladen werden: " + worldName);
                    return;
                }

                applyWorldGuardProtection(world, ownerUuid, ownerName, List.of(), List.of());
                runPostCreateCommands(world);

                runEntityCleanup(world);

                persistWorldMetadataWithRetry(
                    UUID.fromString(ownerUuid),
                    ownerName,
                    worldName,
                    entry.orderLabel(),
                    entry.ticketOrderId(),
                    entry.sourceType(),
                    customers,
                    worldIndex,
                    entry.isPublic(),
                    3,
                    false,
                    false,
                    entry.serverName()
                );
                metadataWorldCreationsInProgress.remove(worldKey);
            }
        }.runTaskLater(plugin, 20L);
    }

    private void ensureWorldCreatedFromMetadata(WorldEntry entry) {
        if (entry == null || !isWorldOnThisServer(entry)) {
            return;
        }

        String worldName = entry.worldName();
        if (worldName == null || worldName.isBlank() || Bukkit.getWorld(worldName) != null) {
            return;
        }

        String worldKey = worldName.toLowerCase(Locale.ROOT);
        if (!metadataWorldCreationsInProgress.add(worldKey)) {
            return;
        }

        createWorldFromMetadata(entry);
    }

    public void executeVerify(Player player, String codeRaw) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.VERIFY, true)) {
            return;
        }

        String code = codeRaw == null ? "" : codeRaw.trim();
        if (!code.matches("\\d{4}")) {
            send(player, "usage.verify");
            return;
        }

        String baseUrl = resolveProfileApiBaseUrl();
        String apiToken = resolveProfileApiToken();
        if (baseUrl == null || baseUrl.isBlank() || apiToken == null || apiToken.isBlank()) {
            send(player, "verify.not-configured");
            return;
        }

        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String payload = "{\"mcName\":\"" + player.getName() + "\",\"code\":\"" + code + "\",\"playerName\":\"" + player.getName() + "\"}";

        send(player, "verify.progress");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String message;
            String resolvedRole = null;
            boolean hasOpenTicket = false;
            try {
                HttpResponse<String> response = postWithFallback(
                    normalizedBase,
                    apiToken,
                    "/auth/profile/minecraft/verify/confirm-server",
                    "/minecraft/verify/confirm-server",
                    payload
                );
                String body = response.body() == null ? "" : response.body();
                boolean verified = response.statusCode() / 100 == 2 && jsonBooleanFieldIsTrue(body, "verified");

                if (!verified && shouldRetryVerifyOnSecondaryBackend(normalizedBase, body)) {
                    HttpResponse<String> secondaryResponse = postVerifyOnSecondaryBackend(normalizedBase, payload);
                    if (secondaryResponse != null) {
                        response = secondaryResponse;
                        body = response.body() == null ? "" : response.body();
                        verified = response.statusCode() / 100 == 2 && jsonBooleanFieldIsTrue(body, "verified");
                    }
                }

                if (verified) {
                    resolvedRole = normalizeRole(extractJsonString(body, "role"));
                    hasOpenTicket = hasAssignedOpenTicket(player.getName());
                    message = configuredMessageLine(
                        "verify.success",
                        "&aMinecraft-Profil erfolgreich verifiziert. &7Rolle: &f%role%",
                        "%role%",
                        displayRoleLabel(resolvedRole)
                    );
                } else {
                    String error = extractJsonString(body, "error");
                    if (error == null || error.isBlank()) {
                        error = extractJsonString(body, "detail");
                    }
                    if (error == null || error.isBlank()) {
                        error = "Code ungültig oder abgelaufen.";
                    }
                    message = configuredMessageLine("verify.failed", "&cVerify fehlgeschlagen: &f%error%", "%error%", error);
                }
            } catch (Exception ex) {
                message = configuredMessageLine("verify.failed", "&cVerify fehlgeschlagen: &f%error%", "%error%", ex.getMessage());
            }

            String finalMessage = message;
            String finalRole = resolvedRole;
            boolean finalHasOpenTicket = hasOpenTicket;
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.sendMessage(finalMessage);
                if (finalRole != null && player.isOnline()) {
                    applyLuckPermsRole(player, finalRole, true, finalHasOpenTicket, true);
                }
            });
        });
    }

    public void executeUnverify(Player player) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.UNVERIFY, true)) {
            return;
        }

        String baseUrl = resolveProfileApiBaseUrl();
        String apiToken = resolveProfileApiToken();
        if (baseUrl == null || baseUrl.isBlank() || apiToken == null || apiToken.isBlank()) {
            send(player, "unverify.not-configured");
            return;
        }

        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String payload = "{\"mcName\":\"" + player.getName() + "\",\"playerName\":\"" + player.getName() + "\"}";

        send(player, "unverify.progress");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String message;
            boolean success = false;
            try {
                HttpResponse<String> response = postWithFallback(
                    normalizedBase,
                    apiToken,
                    "/auth/profile/minecraft/verify/unverify-server",
                    "/minecraft/verify/unverify-server",
                    payload
                );
                String body = response.body() == null ? "" : response.body();

                boolean alreadyUnverified = jsonBooleanFieldIsTrue(body, "alreadyUnverified");
                if (response.statusCode() / 100 == 2 && jsonBooleanFieldIsTrue(body, "ok")) {
                    message = configuredMessageLine("unverify.success", "&aMinecraft-Verifizierung wurde entfernt.");
                    success = true;
                } else if (alreadyUnverified) {
                    message = configuredMessageLine("unverify.already", "&eDu bist bereits unverifiziert.");
                    // No-op: lokale Gruppen trotzdem konsistent auf unverifiziert/default setzen.
                    success = true;
                } else {
                    String error = extractJsonString(body, "error");
                    if (error == null || error.isBlank()) {
                        error = extractJsonString(body, "detail");
                    }
                    if (error == null || error.isBlank()) {
                        error = "Unverify fehlgeschlagen.";
                    }
                    message = configuredMessageLine("unverify.failed", "&cUnverify fehlgeschlagen: &f%error%", "%error%", error);
                }
            } catch (Exception ex) {
                message = configuredMessageLine("unverify.failed", "&cUnverify fehlgeschlagen: &f%error%", "%error%", ex.getMessage());
            }

            String finalMessage = message;
            boolean finalSuccess = success;
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.sendMessage(finalMessage);
                if (finalSuccess && player.isOnline()) {
                    applyLuckPermsRole(player, "default", false, false, true);
                }
            });
        });
    }

    public void syncOnlineMinecraftRoles() {
        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (Player onlinePlayer : onlinePlayers) {
            syncMinecraftRoleForPlayer(onlinePlayer);
        }
    }

    private void syncMinecraftRoleForPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        String baseUrl = resolveProfileApiBaseUrl();
        String apiToken = resolveProfileApiToken();
        if (baseUrl == null || baseUrl.isBlank() || apiToken == null || apiToken.isBlank()) {
            return;
        }

        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String playerName = player.getName();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String resolvedRole = null;
            boolean verified = false;
            boolean hasOpenTicket = false;
            try {
                String query = URLEncoder.encode(playerName, StandardCharsets.UTF_8);
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalizedBase + "/auth/profile/minecraft/verify/role-sync-server?playerName=" + query))
                    .header("Accept", "application/json")
                    .header("X-API-Token", apiToken)
                    .GET()
                    .build();
                HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body() == null ? "" : response.body();
                if (response.statusCode() / 100 == 2 && jsonBooleanFieldIsTrue(body, "ok")) {
                    resolvedRole = normalizeRole(extractJsonString(body, "role"));
                    verified = jsonBooleanFieldIsTrue(body, "verified");
                    hasOpenTicket = hasAssignedOpenTicket(playerName);
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Role-Sync fehlgeschlagen für " + playerName + ": " + ex.getMessage());
            }

            String finalRole = resolvedRole;
            boolean finalVerified = verified;
            boolean finalHasOpenTicket = hasOpenTicket;
            if (finalRole == null) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(player.getUniqueId());
                if (online != null && online.isOnline()) {
                    applyLuckPermsRole(online, finalRole, finalVerified, finalHasOpenTicket, false);
                }
            });
        });
    }

    private boolean hasAssignedOpenTicket(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return false;
        }
        return !repository.listAssignedOpenOrderIds(playerName).isEmpty();
    }

    private HttpResponse<String> postWithFallback(
        String normalizedBase,
        String apiToken,
        String primaryPath,
        String fallbackPath,
        String payload
    ) throws Exception {
        HttpResponse<String> primary = postJson(normalizedBase + primaryPath, apiToken, payload);
        if (primary.statusCode() != 404) {
            return primary;
        }
        return postJson(normalizedBase + fallbackPath, apiToken, payload);
    }

    private HttpResponse<String> postJson(String endpoint, String apiToken, String payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiToken)
            .header("X-API-Token", apiToken)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private boolean shouldRetryVerifyOnSecondaryBackend(String usedBaseUrl, String responseBody) {
        String secondaryBase = resolveSecondaryVerifyBaseUrl(usedBaseUrl);
        if (secondaryBase == null || secondaryBase.isBlank()) {
            return false;
        }

        String error = extractJsonString(responseBody, "error");
        if (error == null || error.isBlank()) {
            error = extractJsonString(responseBody, "detail");
        }
        if (error == null || error.isBlank()) {
            return false;
        }

        String normalized = error.toLowerCase(Locale.ROOT);
        return normalized.contains("ung") && normalized.contains("abgelaufen")
            || normalized.contains("invalid") && normalized.contains("expired");
    }

    private HttpResponse<String> postVerifyOnSecondaryBackend(String usedBaseUrl, String payload) throws Exception {
        String secondaryBase = resolveSecondaryVerifyBaseUrl(usedBaseUrl);
        if (secondaryBase == null || secondaryBase.isBlank()) {
            return null;
        }
        String secondaryToken = resolveApiTokenForBaseUrl(secondaryBase);
        if (secondaryToken == null || secondaryToken.isBlank()) {
            return null;
        }

        return postWithFallback(
            secondaryBase,
            secondaryToken,
            "/auth/profile/minecraft/verify/confirm-server",
            "/minecraft/verify/confirm-server",
            payload
        );
    }

    private String resolveSecondaryVerifyBaseUrl(String usedBaseUrl) {
        String normalizedUsed = trimTrailingSlash(usedBaseUrl);
        String profileBase = trimTrailingSlash(resolveProfileApiBaseUrl());
        String worldsBase = trimTrailingSlash(plugin.getConfig().getString("api.base-url", ""));

        if (!profileBase.isBlank() && !profileBase.equalsIgnoreCase(normalizedUsed)) {
            return profileBase;
        }
        if (!worldsBase.isBlank() && !worldsBase.equalsIgnoreCase(normalizedUsed)) {
            return worldsBase;
        }
        return "";
    }

    private String resolveApiTokenForBaseUrl(String baseUrl) {
        String normalizedBase = trimTrailingSlash(baseUrl);
        String profileBase = trimTrailingSlash(resolveProfileApiBaseUrl());
        String worldsBase = trimTrailingSlash(plugin.getConfig().getString("api.base-url", ""));

        if (!profileBase.isBlank() && profileBase.equalsIgnoreCase(normalizedBase)) {
            return resolveProfileApiToken();
        }
        if (!worldsBase.isBlank() && worldsBase.equalsIgnoreCase(normalizedBase)) {
            return resolveWorldsApiToken();
        }

        String profileToken = resolveProfileApiToken();
        if (profileToken != null && !profileToken.isBlank()) {
            return profileToken;
        }
        return resolveWorldsApiToken();
    }

    private String normalizeRole(String roleRaw) {
        String role = (roleRaw == null ? "" : roleRaw.trim().toLowerCase(Locale.ROOT));
        return switch (role) {
            case "team", "kunde" -> role;
            default -> "default";
        };
    }

    private String displayRoleLabel(String role) {
        return switch (normalizeRole(role)) {
            case "team" -> "Team";
            case "kunde" -> "Kunde";
            default -> "Mitglied";
        };
    }

    private void applyLuckPermsRole(Player player, String role, boolean verified, boolean hasOpenTicket, boolean force) {
        String roleKey = normalizeRole(role);
        UUID playerId = player.getUniqueId();
        String stateKey = roleKey + "|verified=" + verified + "|ticket=" + hasOpenTicket;
        String current = lastAppliedLuckPermsState.get(playerId);
        if (!force && stateKey.equals(current)) {
            return;
        }

        String teamGroup = plugin.getConfig().getString("luckperms.group-team", "team");
        String kundeGroup = plugin.getConfig().getString("luckperms.group-kunde", "kunde");
        String defaultGroup = plugin.getConfig().getString("luckperms.group-default", "default");
        String verifiedGroup = plugin.getConfig().getString("luckperms.group-verified", "mitglied");

        Set<String> managedGroups = new LinkedHashSet<>();
        for (String candidate : List.of(teamGroup, kundeGroup, defaultGroup, verifiedGroup)) {
            if (candidate == null) {
                continue;
            }
            String normalizedCandidate = candidate.trim();
            if (!normalizedCandidate.isBlank()) {
                managedGroups.add(normalizedCandidate);
            }
        }

        Set<String> targetGroups = new LinkedHashSet<>();
        if (defaultGroup != null && !defaultGroup.isBlank()) {
            // Die Default-Gruppe bleibt immer am User und wird bei jedem Sync ggf. erneut gesetzt.
            targetGroups.add(defaultGroup.trim());
        }

        if (verified) {
            if (verifiedGroup != null && !verifiedGroup.isBlank()) {
                targetGroups.add(verifiedGroup.trim());
            }
            if (roleKey.equals("team") && teamGroup != null && !teamGroup.isBlank()) {
                targetGroups.add(teamGroup.trim());
            }
            boolean shouldHaveKunde = roleKey.equals("kunde") || hasOpenTicket;
            if (shouldHaveKunde && kundeGroup != null && !kundeGroup.isBlank()) {
                targetGroups.add(kundeGroup.trim());
            }
        }

        if (targetGroups.isEmpty() && defaultGroup != null && !defaultGroup.isBlank()) {
            targetGroups.add(defaultGroup.trim());
        }

        boolean executed = true;
        for (String managedGroup : managedGroups) {
            boolean shouldHave = targetGroups.stream().anyMatch(group -> group.equalsIgnoreCase(managedGroup));
            if (shouldHave) {
                executed &= Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + player.getName() + " parent add " + managedGroup);
            } else {
                executed &= dispatchLuckPermsParentRemove(player.getName(), managedGroup);
            }
        }

        if (executed) {
            lastAppliedLuckPermsState.put(playerId, stateKey);
        } else {
            plugin.getLogger().warning("LuckPerms-Rollen konnten nicht vollständig gesetzt werden für " + player.getName() + ": " + targetGroups);
        }
    }

    private boolean dispatchLuckPermsParentRemove(String playerName, String group) {
        if (group == null || group.isBlank()) {
            return true;
        }
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + playerName + " parent remove " + group.trim());
    }

    private String resolveProfileApiBaseUrl() {
        String profileBase = plugin.getConfig().getString("api.profile-base-url", "");
        if (profileBase != null && !profileBase.isBlank()) {
            return profileBase;
        }
        return plugin.getConfig().getString("api.base-url", "");
    }

    private String resolveProfileApiToken() {
        String profileToken = plugin.getConfig().getString("api.profile-token", "");
        if (profileToken != null && !profileToken.isBlank()) {
            return profileToken;
        }
        return resolveWorldsApiToken();
    }

    private String resolveWorldsApiToken() {
        String apiToken = plugin.getConfig().getString("api.token", "");
        if (apiToken != null && !apiToken.isBlank()) {
            return apiToken;
        }
        String envToken = System.getenv("API_TOKEN");
        if (envToken != null && !envToken.isBlank()) {
            return envToken;
        }
        envToken = System.getenv("CLANIMG_API_TOKEN");
        if (envToken != null && !envToken.isBlank()) {
            return envToken;
        }
        return plugin.getConfig().getString("api.token", "");
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String out = value.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    public void executeNavSetSpawn(Player player, String worldName) {
        if (!hasPermission(player, Permissions.USE, true)) {
            return;
        }
        if (!player.hasPermission(Permissions.NAV_SETSPAWN) && !hasPermission(player, Permissions.SETSPAWN, true)) {
            return;
        }
        handleSetSpawn(player, worldName);
    }

    public void executeNavStatus(CommandSender sender, String worldNameArg) {
        String worldName = worldNameArg == null ? "" : worldNameArg.trim();

        if (sender instanceof Player player) {
            if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_STATUS, true)) {
                return;
            }
            if (worldName.isBlank()) {
                worldName = player.getWorld().getName();
            }
        } else if (sender instanceof ConsoleCommandSender) {
            if (worldName.isBlank()) {
                sendWithPrefix(sender, "usage.nav.status", "Usage: /nav status <world>");
                return;
            }
        }

        if (worldName.isBlank()) {
            sendWithPrefix(sender, "usage.nav.status", "Usage: /nav status <world>");
            return;
        }

        Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
        if (entryOpt.isEmpty()) {
            sendWithPrefix(sender, "nav.status.world-not-found", "Welt nicht gefunden: %world%", "%world%", worldName);
            return;
        }

        WorldEntry entry = entryOpt.get();
        String lifecycle = entry.isArchived() ? "archiviert" : "aktiv";
        String visibility = entry.isPublic() ? "oeffentlich" : "privat";
        String sourceType = (entry.sourceType() == null || entry.sourceType().isBlank()) ? "-" : entry.sourceType();
        String ticketOrderId = (entry.ticketOrderId() == null || entry.ticketOrderId().isBlank()) ? "-" : entry.ticketOrderId();

        sendNoPrefixConfigured(sender, "nav.status.header", "[WorldsGUI] Status fuer Welt %world%", "%world%", entry.worldName());
        sendNoPrefixConfigured(sender, "nav.status.lifecycle", "- lifecycle: %lifecycle%", "%lifecycle%", lifecycle);
        sendNoPrefixConfigured(sender, "nav.status.visibility", "- visibility: %visibility%", "%visibility%", visibility);
        sendNoPrefixConfigured(sender, "nav.status.source-type", "- sourceType: %sourceType%", "%sourceType%", sourceType);
        sendNoPrefixConfigured(sender, "nav.status.ticket-order-id", "- ticketOrderId: %ticketOrderId%", "%ticketOrderId%", ticketOrderId);
    }

    public void executeNavMyWorldCreate(Player player, String worldName) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_MY_WORLD_CREATE, true)) {
            return;
        }

        String normalizedWorld = worldName == null ? "" : worldName.trim();
        if (!WORLD_NAME_PATTERN.matcher(normalizedWorld).matches()) {
            send(player, "world-name.invalid");
            return;
        }

        if (repository.findByWorldName(normalizedWorld).isPresent()) {
            send(player, "world.already-exists");
            return;
        }

        createWorld(player, normalizedWorld, null, false, "private", null, List.of());
    }

    public void executeNavMyWorldDelete(Player player, String worldName, boolean confirmed) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_MY_WORLD_DELETE, true)) {
            return;
        }
        if (!confirmed) {
            send(player, "usage.nav.my-world.delete");
            return;
        }
        deleteWorld(player, worldName);
    }

    public void executeNavMyWorldOpen(Player player, String worldName) {
        setWorldPublic(player, worldName, true, Permissions.NAV_MY_WORLD_OPEN);
    }

    public void executeNavMyWorldClosed(Player player, String worldName) {
        setWorldPublic(player, worldName, false, Permissions.NAV_MY_WORLD_CLOSED);
    }

    public void executeNavJoinEnable(Player player, String orderLabel) {
        setTicketWorldPublic(player, orderLabel, true, Permissions.NAV_JOIN_ENABLE);
    }

    public void executeNavJoinDisable(Player player, String orderLabel) {
        setTicketWorldPublic(player, orderLabel, false, Permissions.NAV_JOIN_DISABLE);
    }

    public void executeNavMyWorldTrust(Player player, String worldName, String targetPlayer) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_MY_WORLD_TRUST, true)) {
            return;
        }

        String normalizedTarget = normalizePlayerName(targetPlayer);
        if (normalizedTarget == null) {
            sendPlainConfigured(player, "player-name.invalid", "&cUngültiger Spielername.");
            return;
        }

        Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
        if (entryOpt.isEmpty()) {
            send(player, "world-not-found");
            return;
        }

        WorldEntry entry = entryOpt.get();
        if (!entry.ownerUuid().equals(player.getUniqueId().toString()) && !player.hasPermission(Permissions.ADMIN)) {
            send(player, "not-world-owner");
            return;
        }

        if (!containsIgnoreCase(entry.invitedPlayers(), normalizedTarget)) {
            sendPlainConfigured(player, "trust.requires-invite", "&cSpieler ist nicht eingeladen und kann nicht getrusted werden.");
            return;
        }

        List<String> trusted = new ArrayList<>(entry.trustedPlayers());
        if (containsIgnoreCase(trusted, normalizedTarget)) {
            sendPlainConfigured(player, "trust.already", "&eSpieler ist bereits getrusted.");
            return;
        }

        trusted.add(normalizedTarget);
        if (!repository.setTrustedPlayers(worldName, trusted)) {
            sendPlainConfigured(player, "trust.save-failed", "&cTrust konnte nicht gespeichert werden (API-Fehler).");
            return;
        }
        refreshWorldGuardProtection(worldName);
        sendPlainConfigured(
            player,
            "trust.success",
            "&aSpieler &f%player% &ahat jetzt Baurechte in &f%world%&a.",
            "%player%",
            normalizedTarget,
            "%world%",
            worldName
        );
    }

    public void executeNavMyWorldUntrust(Player player, String worldName, String targetPlayer) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_MY_WORLD_UNTRUST, true)) {
            return;
        }

        String normalizedTarget = normalizePlayerName(targetPlayer);
        if (normalizedTarget == null) {
            sendPlainConfigured(player, "player-name.invalid", "&cUngültiger Spielername.");
            return;
        }

        Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
        if (entryOpt.isEmpty()) {
            send(player, "world-not-found");
            return;
        }

        WorldEntry entry = entryOpt.get();
        if (!entry.ownerUuid().equals(player.getUniqueId().toString()) && !player.hasPermission(Permissions.ADMIN)) {
            send(player, "not-world-owner");
            return;
        }

        List<String> trusted = new ArrayList<>(entry.trustedPlayers());
        if (!removeIgnoreCase(trusted, normalizedTarget)) {
            sendPlainConfigured(player, "untrust.none", "&eSpieler hat aktuell keinen Trust-Status.");
            return;
        }

        if (!repository.setTrustedPlayers(worldName, trusted)) {
            sendPlainConfigured(player, "untrust.save-failed", "&cUntrust konnte nicht gespeichert werden (API-Fehler).");
            return;
        }
        refreshWorldGuardProtection(worldName);
        sendPlainConfigured(player, "untrust.success", "&aTrust für &f%player% &awurde entfernt.", "%player%", normalizedTarget);
    }

    public void executeNavMyWorldInvite(Player player, String worldName, String targetPlayer) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_MY_WORLD_INVITE, true)) {
            return;
        }
        invitePlayerToWorld(player, worldName, targetPlayer);
    }

    public void executeNavMyWorldRemove(Player player, String worldName, String targetPlayer) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_MY_WORLD_REMOVE, true)) {
            return;
        }
        removePlayerFromWorld(player, worldName, targetPlayer);
    }

    public void executeNavMyWorldRename(Player player, String worldName, String[] args) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_MY_WORLD_RENAME, true)) {
            return;
        }
        handleRenameCommand(player, worldName, args);
    }

    public void executeNavMyWorldIcon(Player player, String worldName, String[] args) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_MY_WORLD_ICON, true)) {
            return;
        }
        handleIconCommand(player, worldName, args);
    }

    public void executeNavMyWorldClone(Player player, String sourceWorldName, String targetPlayerName) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_MY_WORLD_CLONE, true)) {
            return;
        }

        String sourceName = sourceWorldName == null ? "" : sourceWorldName.trim();
        if (sourceName.isBlank()) {
            send(player, "clone.usage");
            return;
        }

        Optional<WorldEntry> sourceEntryOpt = repository.findByWorldName(sourceName);
        if (sourceEntryOpt.isEmpty()) {
            send(player, "world-not-found");
            return;
        }

        WorldEntry sourceEntry = sourceEntryOpt.get();
        boolean isOwner = sourceEntry.ownerUuid().equals(player.getUniqueId().toString());
        if (!isOwner && !player.hasPermission(Permissions.ADMIN)) {
            send(player, "not-world-owner");
            return;
        }

        if (!isWorldOnThisServer(sourceEntry)) {
            send(player, "clone.source-not-on-this-server");
            return;
        }

        CloneTarget target = resolveCloneTarget(player, targetPlayerName);
        if (target == null) {
            return;
        }

        String cloneWorldName = generateCloneWorldName(target.ownerName());
        if (cloneWorldName.isBlank()) {
            send(player, "clone.name-generation-failed");
            return;
        }

        boolean cloneTriggered = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv clone " + sourceEntry.worldName() + " " + cloneWorldName);
        if (!cloneTriggered) {
            send(player, "clone.mv-failed");
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            World cloned = Bukkit.getWorld(cloneWorldName);
            if (cloned == null) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv load " + cloneWorldName);
                cloned = Bukkit.getWorld(cloneWorldName);
            }
            if (cloned == null) {
                send(player, "clone.load-failed", "%world%", cloneWorldName);
                return;
            }

            int worldIndex = repository.nextWorldIndex(target.ownerUuid());
            boolean persisted = repository.insertWorld(
                cloneWorldName,
                target.ownerUuid(),
                target.ownerName(),
                null,
                null,
                "private",
                null,
                List.of(),
                worldIndex,
                cloneWorldName,
                sourceEntry.iconMaterial(),
                false,
                false,
                sourceEntry.serverName()
            );

            if (!persisted) {
                send(player, "clone.db-failed", "%world%", cloneWorldName);
                return;
            }

            applyWorldGuardProtection(cloned, target.ownerUuid(), target.ownerName(), List.of(), List.of());
            runPostCreateCommands(cloned);

            send(player, "clone.success", "%source_world%", sourceEntry.worldName(), "%target_world%", cloneWorldName);
            if (target.ownerUuid().equals(player.getUniqueId().toString())) {
                send(player, "clone.owner-self");
            } else {
                send(player, "clone.owner-other", "%owner%", target.ownerName());
            }
        }, 20L);
    }

    private CloneTarget resolveCloneTarget(Player actor, String targetPlayerName) {
        if (targetPlayerName == null || targetPlayerName.isBlank()) {
            return new CloneTarget(actor.getUniqueId().toString(), actor.getName());
        }

        String normalized = normalizePlayerName(targetPlayerName);
        if (normalized == null) {
            send(actor, "player-name.invalid");
            return null;
        }

        Player online = Bukkit.getPlayerExact(normalized);
        if (online != null) {
            return new CloneTarget(online.getUniqueId().toString(), online.getName());
        }

        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(normalized);
        if (cached != null && cached.getUniqueId() != null) {
            String ownerName = cached.getName() == null || cached.getName().isBlank() ? normalized : cached.getName();
            return new CloneTarget(cached.getUniqueId().toString(), ownerName);
        }

        send(actor, "clone.target-not-found", "%player%", normalized);
        return null;
    }

    private String generateCloneWorldName(String ownerName) {
        String safeOwner = ownerName == null || ownerName.isBlank() ? "world" : ownerName.trim();
        for (int attempt = 0; attempt < 200; attempt++) {
            int localId = ThreadLocalRandom.current().nextInt(1000, 10_000);
            String candidate = safeOwner + "-" + localId;
            if (isLocalWorldNameAvailable(candidate) && repository.findByWorldName(candidate).isEmpty()) {
                return candidate;
            }
        }
        return "";
    }

    public void executeNavCreate(Player player, String worldName, String orderLabel) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_CREATE, true)) {
            return;
        }

        String normalizedWorld = worldName.trim();
        if (!WORLD_NAME_PATTERN.matcher(normalizedWorld).matches()) {
            send(player, "world-name.invalid");
            return;
        }

        String normalizedOrder = orderLabel.trim().toUpperCase(Locale.ROOT);
        if (!ORDER_LABEL_PATTERN.matcher(normalizedOrder).matches()) {
            send(player, "order.invalid-format");
            return;
        }

        if (repository.findByWorldName(normalizedWorld).isPresent()) {
            send(player, "world.already-exists");
            return;
        }

        createWorld(player, normalizedWorld, normalizedOrder, false, "ticket", normalizedOrder, List.of());
    }

    /**
     * Neuer Wizard-Flow aus guis.yml: create-world (world-type wählen) -> select-server (Server wählen)
     * -> confirm-command "nav create %selection:world-type% %selection:server%". Der Weltname wird
     * serverseitig automatisch im Format <spieler>-<id> generiert (kein manueller Name wie bei
     * /nav my-world create).
     */
    public void executeNavCreateWizard(Player player, String worldTypeArg, String serverIdArg) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_MY_WORLD_CREATE, true)) {
            return;
        }

        String normalizedType = worldTypeArg == null ? "" : worldTypeArg.trim().toUpperCase(Locale.ROOT);
        boolean voidWorld;
        if ("VOID".equals(normalizedType)) {
            voidWorld = true;
        } else if ("FLAT".equals(normalizedType)) {
            voidWorld = false;
        } else {
            send(player, "create.invalid-world-type");
            return;
        }

        String normalizedServer = serverIdArg == null ? "" : serverIdArg.trim();
        if (normalizedServer.isBlank()) {
            send(player, "create.server-missing");
            return;
        }

        if (isBlockedServerName(normalizedServer)) {
            send(player, "create.server-blocked", "%server%", normalizedServer);
            return;
        }

        List<String> selectableServers = resolveSelectableServerNames();
        if (!selectableServers.isEmpty() && selectableServers.stream().noneMatch(server -> server.equalsIgnoreCase(normalizedServer))) {
            send(player, "create.server-unknown", "%server%", normalizedServer);
            return;
        }

        if (voidWorld && Bukkit.getPluginManager().getPlugin("VoidGen") == null) {
            send(player, "create.voidgen-missing");
            return;
        }

        String worldName = generateLocalWorldName(player);

        createWorld(player, worldName, null, false, "private", null, List.of(), voidWorld, false, true, normalizedServer);
    }

    public void executeNavDelete(Player player, String worldName) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_DELETE, true)) {
            return;
        }

        Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
        if (entryOpt.isEmpty()) {
            send(player, "world-not-found");
            return;
        }

        WorldEntry entry = entryOpt.get();
        if (!entry.ownerUuid().equals(player.getUniqueId().toString()) && !player.hasPermission(Permissions.ADMIN)) {
            send(player, "not-world-owner");
            return;
        }

        int confirmCode = ThreadLocalRandom.current().nextInt(100, 1000);
        long expiresAt = System.currentTimeMillis() + 30_000L;
        boolean archiveOnly = isTicketWorld(entry);
        PendingDeleteConfirmation pending = new PendingDeleteConfirmation(worldName, confirmCode, expiresAt, archiveOnly);
        pendingDeleteByPlayer.put(player.getUniqueId(), pending);

        if (archiveOnly) {
            send(player, "delete-confirm-archive", "%code%", String.valueOf(confirmCode));
        } else {
            send(player, "delete-confirm-delete", "%code%", String.valueOf(confirmCode));
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingDeleteConfirmation current = pendingDeleteByPlayer.get(player.getUniqueId());
            if (current != null && current.code() == confirmCode) {
                pendingDeleteByPlayer.remove(player.getUniqueId());
                send(player, "delete-confirm-expired");
            }
        }, 20L * 30);
    }

    public void executeNavConfirm(Player player, String codeRaw) {
        if (!hasPermission(player, Permissions.USE, true)) {
            return;
        }
        if (!player.hasPermission(Permissions.NAV_CONFIRM) && !player.hasPermission(Permissions.NAV_CONFIRM_LEGACY)) {
            send(player, "no-permission");
            return;
        }

        PendingDeleteConfirmation pending = pendingDeleteByPlayer.get(player.getUniqueId());
        if (pending == null) {
            send(player, "delete-confirm-none");
            return;
        }

        int code;
        try {
            code = Integer.parseInt(codeRaw);
        } catch (NumberFormatException ex) {
            send(player, "delete-confirm-numeric");
            return;
        }

        if (pending.expiresAtEpochMs() < System.currentTimeMillis()) {
            pendingDeleteByPlayer.remove(player.getUniqueId());
            send(player, "delete-confirm-expired");
            return;
        }

        if (pending.code() != code) {
            send(player, "delete-confirm-invalid");
            return;
        }

        pendingDeleteByPlayer.remove(player.getUniqueId());
        if (pending.archiveOnly()) {
            archiveWorld(player, pending.worldName());
        } else {
            deleteWorld(player, pending.worldName());
        }
    }

    public List<String> listOwnedWorldNames(Player player) {
        List<WorldEntry> worlds = repository.listOwnWorlds(player.getUniqueId().toString());
        List<String> names = new ArrayList<>(worlds.size());
        for (WorldEntry world : worlds) {
            names.add(world.worldName());
        }
        return names;
    }

    public List<String> listOpenTicketOrderIds(Player player) {
        return repository.listAssignedOpenOrderIds(player.getName());
    }

    public List<String> listCloneTargetPlayerNames(Player player) {
        Set<String> names = new LinkedHashSet<>();
        names.add(player.getName());

        for (Player online : Bukkit.getOnlinePlayers()) {
            names.add(online.getName());
        }

        for (WorldEntry entry : repository.listOwnWorlds(player.getUniqueId().toString())) {
            if (entry.ownerName() != null && !entry.ownerName().isBlank()) {
                names.add(entry.ownerName());
            }
            names.addAll(entry.invitedPlayers());
            names.addAll(entry.trustedPlayers());
        }

        return new ArrayList<>(names);
    }

    public List<String> listImportableWorldNames(Player player) {
        Set<String> candidates = new LinkedHashSet<>();
        Set<String> excluded = Set.of("world", "world_nether", "world_the_end");

        for (World world : Bukkit.getWorlds()) {
            String name = world.getName();
            if (name != null && !name.isBlank() && !excluded.contains(name.toLowerCase(Locale.ROOT))) {
                candidates.add(name);
            }
        }

        File worldContainer = Bukkit.getWorldContainer();
        File[] children = worldContainer == null ? null : worldContainer.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!child.isDirectory()) {
                    continue;
                }
                String name = child.getName();
                if (name == null || name.isBlank() || excluded.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                File levelDat = new File(child, "level.dat");
                if (levelDat.exists()) {
                    candidates.add(name);
                }
            }
        }

        List<String> out = new ArrayList<>();
        for (String name : candidates) {
            if (repository.findByWorldName(name).isEmpty()) {
                out.add(name);
            }
        }
        return out;
    }

    public void executeRename(Player player, String[] args) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.RENAME, true)) {
            return;
        }
        handleRenameCommand(player, args);
    }

    public void executeNavRename(Player player, String worldName, String[] args) {
        if (!hasPermission(player, Permissions.USE, true)) {
            return;
        }
        if (!player.hasPermission(Permissions.NAV_RENAME) && !hasPermission(player, Permissions.RENAME, true)) {
            return;
        }
        handleRenameCommand(player, worldName, args);
    }

    public void executeIcon(Player player, String[] args) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.ICON, true)) {
            return;
        }
        handleIconCommand(player, args);
    }

    public void executeNavIcon(Player player, String worldName, String[] args) {
        if (!hasPermission(player, Permissions.USE, true)) {
            return;
        }
        if (!player.hasPermission(Permissions.NAV_ICON) && !hasPermission(player, Permissions.ICON, true)) {
            return;
        }
        handleIconCommand(player, worldName, args);
    }

    public void ensurePersonalFlatWorldForJoin(Player player) {
        String localServer = resolveLocalServerId();

        if (!localServer.isBlank() && isBlockedServerName(localServer)) {
            if (!warnedBlockedLocalServerForAutoCreate) {
                warnedBlockedLocalServerForAutoCreate = true;
                plugin.getLogger().warning(
                    "Auto-Welt-Erstellung beim Join ist für diesen Server deaktiviert, " +
                    "weil api.blocked-server-names die lokale Server-ID enthält: " + localServer
                );
            }
            return;
        }

        List<WorldEntry> ownWorlds = repository.listOwnWorlds(player.getUniqueId().toString());
        boolean hasEligibleWorld;
        if (localServer.isBlank()) {
            if (!warnedMissingExplicitServerIdForAutoCreate) {
                warnedMissingExplicitServerIdForAutoCreate = true;
                plugin.getLogger().warning(
                    "Auto-Welt-Erstellung beim Join nutzt keine Hostname-Fallback-ID. " +
                    "Setze api.local-server-name oder SIMPLECLOUD_SERVICE_NAME/SIMPLECLOUD_SERVER_ID " +
                    "für servergenaue Erkennung."
                );
            }
            hasEligibleWorld = !ownWorlds.isEmpty();
        } else {
            hasEligibleWorld = ownWorlds.stream()
                .anyMatch(entry -> isSameServerIdentifier(entry.serverName(), localServer));
        }

        if (hasEligibleWorld) {
            return;
        }

        String worldName = generateLocalWorldName(player);
        createWorld(player, worldName, null, false, "private", null, List.of(), false, false, false, localServer);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(player.getUniqueId());
            if (online == null || !online.isOnline()) {
                return;
            }

            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv load " + worldName);
                world = Bukkit.getWorld(worldName);
            }
            if (world == null) {
                return;
            }

            online.teleport(world.getSpawnLocation());
            online.setGameMode(resolvePreferredGameMode(online, null));
            send(online, "autocreate.teleport-success");
        }, 80L);
    }

    public void executeNavMyWorldImport(Player player, String worldName, String targetPlayerName) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_MY_WORLD_IMPORT, true)) {
            return;
        }

        String normalizedWorld = worldName == null ? "" : worldName.trim();
        if (normalizedWorld.isBlank()) {
            send(player, "import.usage");
            return;
        }

        if (repository.findByWorldName(normalizedWorld).isPresent()) {
            send(player, "import.already-registered", "%world%", normalizedWorld);
            return;
        }

        CloneTarget target = resolveCloneTarget(player, targetPlayerName);
        if (target == null) {
            return;
        }

        World world = Bukkit.getWorld(normalizedWorld);
        if (world == null) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv import " + normalizedWorld + " normal");
            world = Bukkit.getWorld(normalizedWorld);
        }
        if (world == null) {
            send(player, "import.load-failed", "%world%", normalizedWorld);
            return;
        }

        int worldIndex = repository.nextWorldIndex(target.ownerUuid());
        boolean persisted = repository.insertWorld(
            normalizedWorld,
            target.ownerUuid(),
            target.ownerName(),
            null,
            null,
            "private",
            null,
            List.of(),
            worldIndex,
            normalizedWorld,
            "GRASS_BLOCK",
            false,
            false,
            resolveLocalServerId()
        );

        if (!persisted) {
            send(player, "import.db-failed", "%world%", normalizedWorld);
            return;
        }

        applyWorldGuardProtection(world, target.ownerUuid(), target.ownerName(), List.of(), List.of());
        runPostCreateCommands(world);

        send(player, "import.success", "%world%", normalizedWorld, "%owner%", target.ownerName());
    }

    public void executeNavCustomerInvite(Player player, String worldName, String targetPlayer) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_INVITE, true)) {
            return;
        }

        invitePlayerToWorld(player, worldName, targetPlayer);
    }

    private void invitePlayerToWorld(Player player, String worldName, String targetPlayer) {

        String normalizedTarget = normalizePlayerName(targetPlayer);
        if (normalizedTarget == null) {
            sendPlainConfigured(player, "player-name.invalid", "&cUngültiger Spielername.");
            return;
        }

        if (normalizedTarget.equalsIgnoreCase(player.getName())) {
            sendPlainConfigured(player, "invite.self-not-allowed", "&cDu kannst dich nicht selbst einladen.");
            return;
        }

        Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
        if (entryOpt.isEmpty()) {
            send(player, "world-not-found");
            return;
        }

        WorldEntry entry = entryOpt.get();
        if (!entry.ownerUuid().equals(player.getUniqueId().toString()) && !player.hasPermission(Permissions.ADMIN)) {
            send(player, "not-world-owner");
            return;
        }

        List<String> invited = new ArrayList<>(entry.invitedPlayers());
        if (containsIgnoreCase(invited, normalizedTarget)) {
            sendPlainConfigured(player, "invite.already", "&eSpieler ist bereits eingeladen.");
            return;
        }

        invited.add(normalizedTarget);
        if (!repository.setInvitedPlayers(worldName, invited)) {
            sendPlainConfigured(player, "invite.save-failed", "&cEinladung konnte nicht gespeichert werden (API-Fehler).");
            return;
        }
        refreshWorldGuardProtection(worldName);
        sendPlainConfigured(
            player,
            "invite.success",
            "&aSpieler &f%player% &awurde für &f%world% &aeingeladen.",
            "%player%",
            normalizedTarget,
            "%world%",
            worldName
        );

        PlayerGuiSession session = playerSessions.getOrCreate(player.getUniqueId());
        if ("select-friend".equalsIgnoreCase(session.currentGuiId())) {
            session.clearParam("search");
            String previousGuiId = session.popHistory();
            String previousPreviousGuiId = session.popHistory();
            String targetGuiId = previousPreviousGuiId != null && !previousPreviousGuiId.isBlank()
                ? previousPreviousGuiId
                : previousGuiId;
            if (targetGuiId != null && !targetGuiId.isBlank()) {
                session.setCurrentGuiId(targetGuiId);
                openGui(player, targetGuiId);
            }
        }
    }

    public void executeNavCustomerRemove(Player player, String worldName, String targetPlayer) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_REMOVE, true)) {
            return;
        }

        removePlayerFromWorld(player, worldName, targetPlayer);
    }

    private void removePlayerFromWorld(Player player, String worldName, String targetPlayer) {

        String normalizedTarget = normalizePlayerName(targetPlayer);
        if (normalizedTarget == null) {
            sendPlainConfigured(player, "player-name.invalid", "&cUngültiger Spielername.");
            return;
        }

        Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
        if (entryOpt.isEmpty()) {
            send(player, "world-not-found");
            return;
        }

        WorldEntry entry = entryOpt.get();
        if (!entry.ownerUuid().equals(player.getUniqueId().toString()) && !player.hasPermission(Permissions.ADMIN)) {
            send(player, "not-world-owner");
            return;
        }

        List<String> invited = new ArrayList<>(entry.invitedPlayers());
        if (!removeIgnoreCase(invited, normalizedTarget)) {
            sendPlainConfigured(player, "remove.not-invited", "&eSpieler ist für diese Welt nicht eingeladen.");
            return;
        }

        List<String> trusted = new ArrayList<>(entry.trustedPlayers());
        removeIgnoreCase(trusted, normalizedTarget);

        if (!repository.setInvitedPlayers(worldName, invited)) {
            sendPlainConfigured(player, "remove.invite-remove-failed", "&cEinladung konnte nicht entfernt werden (API-Fehler).");
            return;
        }
        if (!repository.setTrustedPlayers(worldName, trusted)) {
            sendPlainConfigured(player, "remove.trust-update-failed", "&cTrust-Status konnte nicht aktualisiert werden (API-Fehler).");
            return;
        }
        refreshWorldGuardProtection(worldName);
        sendPlainConfigured(player, "remove.success", "&aEinladung für &f%player% &awurde entfernt.", "%player%", normalizedTarget);
        forcePlayerOutOfWorldIfNeeded(normalizedTarget, worldName);
    }

    public void executeNavCustomerTrust(Player player, String targetPlayer) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_TRUST, true)) {
            return;
        }

        String normalizedTarget = normalizePlayerName(targetPlayer);
        if (normalizedTarget == null) {
            send(player, "player-name.invalid");
            return;
        }

        String worldName = resolveContextWorld(player);
        if (worldName == null) {
            send(player, "no-selected-world");
            return;
        }

        Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
        if (entryOpt.isEmpty()) {
            send(player, "world-not-found");
            return;
        }

        WorldEntry entry = entryOpt.get();
        if (!entry.ownerUuid().equals(player.getUniqueId().toString()) && !player.hasPermission(Permissions.ADMIN)) {
            send(player, "not-world-owner");
            return;
        }

        if (!containsIgnoreCase(entry.invitedPlayers(), normalizedTarget)) {
            send(player, "trust.requires-invite");
            return;
        }

        List<String> trusted = new ArrayList<>(entry.trustedPlayers());
        if (containsIgnoreCase(trusted, normalizedTarget)) {
            send(player, "trust.already");
            return;
        }

        trusted.add(normalizedTarget);
        if (!repository.setTrustedPlayers(worldName, trusted)) {
            send(player, "trust.save-failed");
            return;
        }
        refreshWorldGuardProtection(worldName);
        send(player, "trust.success", "%player%", normalizedTarget, "%world%", worldName);
    }

    public void executeNavCustomerUntrust(Player player, String targetPlayer) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_UNTRUST, true)) {
            return;
        }

        String normalizedTarget = normalizePlayerName(targetPlayer);
        if (normalizedTarget == null) {
            send(player, "player-name.invalid");
            return;
        }

        String worldName = resolveContextWorld(player);
        if (worldName == null) {
            send(player, "no-selected-world");
            return;
        }

        Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
        if (entryOpt.isEmpty()) {
            send(player, "world-not-found");
            return;
        }

        WorldEntry entry = entryOpt.get();
        if (!entry.ownerUuid().equals(player.getUniqueId().toString()) && !player.hasPermission(Permissions.ADMIN)) {
            send(player, "not-world-owner");
            return;
        }

        List<String> trusted = new ArrayList<>(entry.trustedPlayers());
        if (!removeIgnoreCase(trusted, normalizedTarget)) {
            send(player, "untrust.none");
            return;
        }

        if (!repository.setTrustedPlayers(worldName, trusted)) {
            send(player, "untrust.save-failed");
            return;
        }
        refreshWorldGuardProtection(worldName);
        send(player, "untrust.success", "%player%", normalizedTarget);
    }

    public List<String> listInvitedPlayerNames(String worldName) {
        Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
        if (entryOpt.isEmpty()) {
            return List.of();
        }
        return entryOpt.get().invitedPlayers();
    }

    public List<String> listContextInvitedPlayerNames(Player player) {
        String worldName = resolveContextWorld(player);
        if (worldName == null) {
            return List.of();
        }
        return listInvitedPlayerNames(worldName);
    }

    public List<String> listContextTrustedPlayerNames(Player player) {
        String worldName = resolveContextWorld(player);
        if (worldName == null) {
            return List.of();
        }
        Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
        if (entryOpt.isEmpty()) {
            return List.of();
        }
        return entryOpt.get().trustedPlayers();
    }

    public boolean executeDashboardJoin(Player player, String worldName) {
        return joinWorld(player, worldName, false);
    }

    public void handlePlayerJoin(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String pendingWorldName = repository.consumePendingWorldTransfer(player.getName());
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(player.getUniqueId());
                if (online == null || !online.isOnline()) {
                    return;
                }

                if (pendingWorldName != null && !pendingWorldName.isBlank()) {
                    schedulePendingWorldTransferJoin(online, pendingWorldName, 20L, 45);
                    return;
                }

                ensurePersonalFlatWorldForJoin(online);
            });
        });
    }

    public void notifyCustomerActiveTicketOnJoin(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        UUID playerId = player.getUniqueId();
        String playerName = player.getName();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<WorldEntry> match = repository
                .listOpenTicketWorlds()
                .stream()
                .filter(entry -> entry.customers() != null && containsIgnoreCase(entry.customers(), playerName))
                .findFirst();

            if (match.isEmpty()) {
                return;
            }

            String worldName = match.get().worldName();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(playerId);
                if (online == null || !online.isOnline()) {
                    return;
                }

                sendNoPrefix(
                    online,
                    "active-ticket",
                    "%world%", worldName,
                    "%world_link%", "/nav"
                );
            });
        });
    }

    public void handleInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Inventory top = event.getView().getTopInventory();
        Inventory clicked = event.getClickedInventory();
        if (clicked == null || clicked.getType() == InventoryType.PLAYER || clicked != top) {
            return;
        }

        InventoryHolder holder = top.getHolder();
        if (holder instanceof MainHolder mainHolder) {
            event.setCancelled(true);
            handleMainMenuClick(player, event, mainHolder);
            return;
        }

        if (holder instanceof CreateOptionsHolder createOptionsHolder) {
            event.setCancelled(true);
            handleCreateOptionsClick(player, event, createOptionsHolder);
            return;
        }

        if (holder instanceof IconSelectorHolder selectorHolder) {
            event.setCancelled(true);
            handleIconSelectorClick(player, event, selectorHolder);
            return;
        }

        if (holder instanceof RuntimeGuiHolder runtimeHolder) {
            event.setCancelled(true);
            handleRuntimeGuiClick(player, event, runtimeHolder);
        }
    }

    public void handleChat(AsyncChatEvent event) {
        Player player = event.getPlayer();

        GuiTrigger pendingAnvilTrigger = pendingAnvilInputs.remove(player.getUniqueId());
        if (pendingAnvilTrigger != null) {
            event.setCancelled(true);
            String anvilInput = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
            Bukkit.getScheduler().runTask(plugin, () -> completeAnvilInput(player, pendingAnvilTrigger, anvilInput));
            return;
        }

        PendingInput pending = pendingInputs.remove(player.getUniqueId());
        if (pending == null) {
            return;
        }

        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (pending.type() == PendingType.RENAME) {
                applyRename(player, pending.worldName(), input);
            } else if (pending.type() == PendingType.ICON) {
                applyIcon(player, pending.worldName(), input);
            }
            openEditWorldGui(player, pending.worldName());
        });
    }

    private void openEditWorldGui(Player player, String worldName) {
        if (player == null || worldName == null || worldName.isBlank()) {
            return;
        }

        selectedWorldByPlayer.put(player.getUniqueId(), worldName);
        PlayerGuiSession session = playerSessions.getOrCreate(player.getUniqueId());
        session.setCurrentWorld(worldName);
        openGui(player, "edit-world");
    }

    /**
     * Verarbeitet die Eingabe eines anvil-input Triggers (Dialog-API oder Chat-Fallback) und
     * navigiert anschließend gemäß guis.yml zum konfigurierten Ziel-GUI weiter.
     */
    private void completeAnvilInput(Player player, GuiTrigger trigger, String input) {
        PlayerGuiSession session = playerSessions.getOrCreate(player.getUniqueId());
        session.putParam(trigger.paramKey(), input == null ? "" : input);

        if (!trigger.params().isEmpty()) {
            Map<String, String> expandedParams = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : trigger.params().entrySet()) {
                expandedParams.put(entry.getKey(), PlaceholderExpander.expand(entry.getValue(), player, session, Map.of()));
            }
            session.putGuiParams(expandedParams);
        }

        String currentGuiId = session.currentGuiId();
        if (currentGuiId == null || !currentGuiId.equalsIgnoreCase(trigger.guiId())) {
            // Kein History-Eintrag, wenn das Ziel-GUI dasselbe ist (z.B. Such-Filter-Refresh),
            // sonst würde "return" später nur wieder auf dieses GUI selbst zurückführen.
            session.pushCurrentToHistory();
        }
        session.setCurrentGuiId(trigger.guiId());
        openGui(player, trigger.guiId());
    }

    /**
     * Fordert die Eingabe für einen anvil-input Trigger an. Primär über die Minecraft Dialog-API
     * (1.21.6+); falls die Dialog-API aus irgendeinem Grund fehlschlägt (z.B. inkompatibler Client),
     * wird kontrolliert auf eine einfache Chat-Eingabe zurückgefallen.
     */
    private void requestAnvilInput(Player player, PlayerGuiSession session, GuiTrigger trigger) {
        try {
            showAnvilInputDialog(player, trigger);
        } catch (Throwable t) {
            plugin.getLogger().warning("Dialog-API für anvil-input fehlgeschlagen (" + t.getMessage() + "), nutze Chat-Fallback.");
            pendingAnvilInputs.put(player.getUniqueId(), trigger);
            send(player, "anvil.chat-fallback-prompt");
        }
    }

    private void showAnvilInputDialog(Player player, GuiTrigger trigger) {
        Component title = parseFormattedMessage(trigger.anvilTitle());

        TextDialogInput textInput = DialogInput.text("input", title)
            .initial("")
            .maxLength(64)
            .build();

        DialogActionCallback callback = (DialogResponseView view, net.kyori.adventure.audience.Audience audience) -> {
            String input = view.getText("input");
            Bukkit.getScheduler().runTask(plugin, () -> completeAnvilInput(player, trigger, input));
        };

        ActionButton confirmButton = ActionButton.create(
            Component.text("Bestätigen"),
            Component.empty(),
            150,
            DialogAction.customClick(callback, ClickCallback.Options.builder().build())
        );

        DialogBase base = DialogBase.create(
            title,
            title,
            true,
            false,
            DialogBase.DialogAfterAction.CLOSE,
            List.of(),
            List.of(textInput)
        );

        Dialog dialog = Dialog.create(factory -> factory.empty()
            .base(base)
            .type(DialogType.notice(confirmButton)));

        player.showDialog(dialog);
    }

    private void handleMainMenuClick(Player player, InventoryClickEvent event, MainHolder holder) {
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= MAIN_SIZE) {
            return;
        }

        if (slot == 45) {
            if (holder.page() > 0) {
                openMainMenu(player, holder.mode(), holder.page() - 1);
            }
            return;
        }

        if (slot == 53) {
            openMainMenu(player, holder.mode(), holder.page() + 1);
            return;
        }

        if (slot == 49) {
            if (holder.mode() != ViewMode.OWN) {
                return;
            }
            if (guiConfig != null && guiConfig.get("create-world").isPresent()) {
                PlayerGuiSession session = playerSessions.getOrCreate(player.getUniqueId());
                session.setCurrentGuiId("my-worlds");
                session.pushCurrentToHistory();
                session.setCurrentGuiId("create-world");
                openRuntimeGui(player, "create-world");
            } else {
                openCreateOptionsMenu(player, holder.page());
            }
            return;
        }

        if (slot == 48) {
            openMainMenu(player, ViewMode.INVITED, 0);
            return;
        }

        if (slot == 50) {
            ViewMode nextMode = holder.mode() == ViewMode.PUBLIC ? ViewMode.OWN : ViewMode.PUBLIC;
            openMainMenu(player, nextMode, 0);
            return;
        }

        if (slot == 51 && holder.mode() == ViewMode.OWN && player.hasPermission(Permissions.ADMIN)) {
            openMainMenu(player, ViewMode.ARCHIVED, 0);
            return;
        }

        if (slot > 35) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR || !clicked.hasItemMeta()) {
            return;
        }
        ItemMeta meta = clicked.getItemMeta();
        String worldName = meta.getPersistentDataContainer().get(worldKey, PersistentDataType.STRING);
        if (worldName == null || worldName.isBlank()) {
            return;
        }

        if (event.isLeftClick()) {
            joinWorld(player, worldName, true);
            return;
        }

        if (event.isRightClick() && holder.mode() == ViewMode.OWN) {
            Optional<WorldEntry> entry = repository.findByWorldName(worldName);
            if (entry.isPresent() && entry.get().ownerUuid().equals(player.getUniqueId().toString())) {
                selectedWorldByPlayer.put(player.getUniqueId(), worldName);
                PlayerGuiSession session = playerSessions.getOrCreate(player.getUniqueId());
                session.setCurrentWorld(worldName);
                openGui(player, "edit-world");
            }
        }
    }

    private void handleSetSpawn(Player player) {
        handleSetSpawn(player, null);
    }

    private void handleSetSpawn(Player player, String forcedWorldName) {
        String worldName = forcedWorldName != null && !forcedWorldName.isBlank()
            ? forcedWorldName
            : resolveContextWorld(player);
        if (worldName == null) {
            send(player, "no-selected-world");
            return;
        }

        Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
        if (entryOpt.isEmpty()) {
            send(player, "world-not-found");
            return;
        }
        WorldEntry entry = entryOpt.get();
        if (!entry.ownerUuid().equals(player.getUniqueId().toString())) {
            send(player, "not-world-owner");
            return;
        }

        if (!player.getWorld().getName().equals(worldName)) {
            send(player, "setspawn-not-in-world");
            return;
        }

        Location loc = player.getLocation();
        World world = player.getWorld();
        world.setSpawnLocation(loc);
        repository.setSpawn(worldName, loc);
        send(player, "setspawn-success");
    }

    private void handleRenameCommand(Player player, String[] args) {
        handleRenameCommand(player, null, args);
    }

    private void handleRenameCommand(Player player, String forcedWorldName, String[] args) {
        String worldName = forcedWorldName != null && !forcedWorldName.isBlank()
            ? forcedWorldName
            : resolveContextWorld(player);
        if (worldName == null) {
            send(player, "no-selected-world");
            return;
        }

        if (args.length == 0) {
            try {
                showRenameInputDialog(player, worldName);
                return;
            } catch (Throwable t) {
                plugin.getLogger().warning("Dialog-API für Rename fehlgeschlagen (" + t.getMessage() + "), nutze Chat-Fallback.");
            }

            pendingInputs.put(player.getUniqueId(), new PendingInput(PendingType.RENAME, worldName));
            send(player, "rename-prompt");
            return;
        }

        String newName = String.join(" ", args).trim();
        applyRename(player, worldName, newName);
    }

    private void showRenameInputDialog(Player player, String worldName) {
        Component title = parseFormattedMessage("&7Welt-Name ändern");

        TextDialogInput textInput = DialogInput.text("input", title)
            .initial("")
            .maxLength(32)
            .build();

        DialogActionCallback callback = (DialogResponseView view, net.kyori.adventure.audience.Audience audience) -> {
            String input = view.getText("input");
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(player.getUniqueId());
                if (online == null || !online.isOnline()) {
                    return;
                }
                applyRename(online, worldName, input == null ? "" : input.trim());
            });
        };

        ActionButton confirmButton = ActionButton.create(
            Component.text("Bestätigen"),
            Component.empty(),
            150,
            DialogAction.customClick(callback, ClickCallback.Options.builder().build())
        );

        DialogBase base = DialogBase.create(
            title,
            title,
            true,
            false,
            DialogBase.DialogAfterAction.CLOSE,
            List.of(),
            List.of(textInput)
        );

        Dialog dialog = Dialog.create(factory -> factory.empty()
            .base(base)
            .type(DialogType.notice(confirmButton)));

        player.showDialog(dialog);
    }

    private void handleIconCommand(Player player, String[] args) {
        handleIconCommand(player, null, args);
    }

    private void handleIconCommand(Player player, String forcedWorldName, String[] args) {
        String worldName = forcedWorldName != null && !forcedWorldName.isBlank()
            ? forcedWorldName
            : resolveContextWorld(player);
        if (worldName == null) {
            send(player, "no-selected-world");
            return;
        }

        if (args.length == 0) {
            pendingInputs.put(player.getUniqueId(), new PendingInput(PendingType.ICON, worldName));
            send(player, "icon-prompt");
            return;
        }

        applyIcon(player, worldName, args[0]);
    }

    private void applyRename(Player player, String worldName, String input) {
        String newDisplayName = input.trim();
        if (newDisplayName.isBlank()) {
            send(player, "rename-empty");
            return;
        }
        if (newDisplayName.length() > 32) {
            send(player, "rename-too-long");
            return;
        }
        Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
        if (entryOpt.isEmpty()) {
            send(player, "world-not-found");
            return;
        }
        WorldEntry entry = entryOpt.get();
        if (!entry.ownerUuid().equals(player.getUniqueId().toString())) {
            send(player, "not-world-owner");
            return;
        }
        if (!repository.setDisplayName(worldName, newDisplayName)) {
            send(player, "rename.save-failed");
            return;
        }

        send(player, "rename-success", "%name%", newDisplayName);
        if (player.isOnline()) {
            openEditWorldGui(player, worldName);
        }
    }

    private void applyIcon(Player player, String worldName, String input) {
        String normalized = input.trim().toUpperCase(Locale.ROOT);
        Material material = Material.matchMaterial(normalized);
        if (material == null || !material.isItem()) {
            send(player, "icon-invalid");
            return;
        }

        Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
        if (entryOpt.isEmpty()) {
            send(player, "world-not-found");
            return;
        }
        WorldEntry entry = entryOpt.get();
        if (!entry.ownerUuid().equals(player.getUniqueId().toString())) {
            send(player, "not-world-owner");
            return;
        }

        repository.setIcon(worldName, material.name());
        send(player, "icon-success", "%icon%", material.name());
    }

    private void openMainMenu(Player player, ViewMode mode, int page) {
        UUID playerId = player.getUniqueId();
        String playerUuid = playerId.toString();
        String playerName = player.getName();
        boolean admin = player.hasPermission(Permissions.ADMIN);

        if (mode == ViewMode.OWN) {
            playerSessions.getOrCreate(playerId).setCurrentGuiId("my-worlds");
        }

        player.openInventory(buildMainMenu(player.hasPermission(Permissions.ADMIN), mode, page, List.of(), true, player.getWorld().getName()));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<WorldEntry> worlds = listWorldsFor(playerUuid, playerName, admin, mode);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player onlinePlayer = Bukkit.getPlayer(playerId);
                if (onlinePlayer == null || !onlinePlayer.isOnline()) {
                    return;
                }

                InventoryHolder holder = onlinePlayer.getOpenInventory().getTopInventory().getHolder();
                if (!(holder instanceof MainHolder mainHolder) || mainHolder.mode() != mode || mainHolder.page() != page) {
                    return;
                }

                onlinePlayer.openInventory(buildMainMenu(onlinePlayer.hasPermission(Permissions.ADMIN), mode, page, worlds, false, onlinePlayer.getWorld().getName()));
            });
        });
    }

    private Inventory buildMainMenu(boolean admin, ViewMode mode, int page, List<WorldEntry> worlds, boolean loading, String currentWorldName) {
        Inventory inv = Bukkit.createInventory(new MainHolder(mode, page), MAIN_SIZE, titleForMain(mode, page));

        ItemStack filler = namedItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 36; slot <= 44; slot++) {
            inv.setItem(slot, filler);
        }

        if (loading) {
            inv.setItem(22, namedItem(Material.CLOCK, "§eLade Welten ..."));
        } else {
            int start = page * PAGE_SIZE;
            for (int i = 0; i < PAGE_SIZE; i++) {
                int idx = start + i;
                if (idx >= worlds.size()) {
                    break;
                }
                WorldEntry entry = worlds.get(idx);
                inv.setItem(i, worldIcon(entry, mode, currentWorldName));
            }
        }

        if (page > 0) {
            inv.setItem(45, namedItem(Material.ARROW, "§fZurück"));
        }

        int maxPage = Math.max(0, (int) Math.ceil(worlds.size() / (double) PAGE_SIZE) - 1);
        if (page < maxPage) {
            inv.setItem(53, namedItem(Material.ARROW, "§fWeiter"));
        }

        inv.setItem(48, namedItem(Material.ENDER_EYE, "§dEingeladene Welten"));

        if (mode == ViewMode.OWN) {
            inv.setItem(49, namedItem(Material.EMERALD_BLOCK, "§aNeue Welt erstellen"));
            inv.setItem(50, namedItem(Material.COMPASS, "§bTickets ansehen"));
            if (admin) {
                inv.setItem(51, namedItem(Material.DIAMOND, "§cArchivierte Tickets"));
            }
        } else if (mode == ViewMode.PUBLIC) {
            inv.setItem(50, namedItem(Material.COMPASS, "§eMeine Welten ansehen"));
        } else if (mode == ViewMode.ARCHIVED) {
            inv.setItem(50, namedItem(Material.COMPASS, "§bTickets ansehen"));
        } else {
            inv.setItem(50, namedItem(Material.COMPASS, "§bTickets ansehen"));
        }
        return inv;
    }

    private void openCreateOptionsMenu(Player player, int returnPage) {
        String worldName = generateLocalWorldName(player);
        openCreateOptionsMenu(player, worldName, returnPage, false, false);
    }

    private void openCreateOptionsMenu(Player player, String worldName, int returnPage, boolean voidWorld, boolean chunkyEnabled) {
        Inventory inv = Bukkit.createInventory(
            new CreateOptionsHolder(worldName, returnPage, voidWorld, chunkyEnabled),
            CREATE_OPTIONS_SIZE,
            Component.text("Neue Welt erstellen", NamedTextColor.DARK_AQUA)
        );

        ItemStack filler = namedItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < CREATE_OPTIONS_SIZE; slot++) {
            inv.setItem(slot, filler);
        }

        inv.setItem(
            4,
            namedItem(
                Material.PAPER,
                "§fWeltname: §b" + worldName,
                List.of(Component.text("Die Welt wird direkt mit diesem Namen erstellt."))
            )
        );

        inv.setItem(
            11,
            voidWorld
                ? namedItem(Material.BARRIER, "§cVoid", List.of(Component.text("Klicke für Flatworld")))
                : namedItem(Material.GRASS_BLOCK, "§aFlatworld", List.of(Component.text("Klicke für Void")))
        );

        inv.setItem(
            13,
            namedItem(
                Material.EMERALD_BLOCK,
                "§aWelt jetzt erstellen",
                List.of(Component.text("Template: " + (voidWorld ? "Void" : "Flatworld")), Component.text("Chunky: " + (chunkyEnabled ? "Aktiv" : "Deaktiviert")))
            )
        );

        inv.setItem(
            15,
            chunkyEnabled
                ? namedItem(Material.LIME_WOOL, "§aChunky aktiv", List.of(Component.text("Radius: 2500 Blöcke um den Spawn"), Component.text("Klicke zum Deaktivieren")))
                : namedItem(Material.RED_WOOL, "§cChunky deaktiviert", List.of(Component.text("Standard: aus"), Component.text("Klicke zum Aktivieren")))
        );

        inv.setItem(22, namedItem(Material.SPRUCE_DOOR, "§fZurück"));
        player.openInventory(inv);
    }

    private void handleCreateOptionsClick(Player player, InventoryClickEvent event, CreateOptionsHolder holder) {
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= CREATE_OPTIONS_SIZE) {
            return;
        }

        switch (slot) {
            case 11 -> openCreateOptionsMenu(player, holder.worldName(), holder.returnPage(), !holder.voidWorld(), holder.chunkyEnabled());
            case 15 -> openCreateOptionsMenu(player, holder.worldName(), holder.returnPage(), holder.voidWorld(), !holder.chunkyEnabled());
            case 22 -> openGui(player, "my-worlds");
            case 13 -> {
                if (holder.voidWorld() && Bukkit.getPluginManager().getPlugin("VoidGen") == null) {
                    send(player, "create.voidgen-missing");
                    return;
                }

                createWorld(player, holder.worldName(), null, false, "private", null, List.of(), holder.voidWorld(), holder.chunkyEnabled(), true);
            }
            default -> {
            }
        }
    }

    private List<WorldEntry> listWorldsFor(Player player, ViewMode mode) {
        return listWorldsFor(player.getUniqueId().toString(), player.getName(), player.hasPermission(Permissions.ADMIN), mode);
    }

    private List<WorldEntry> listWorldsFor(String ownerUuid, String playerName, boolean admin, ViewMode mode) {
        if (mode == ViewMode.OWN) {
            return repository.listOwnWorlds(ownerUuid)
                .stream()
                .filter(entry -> !isTicketWorld(entry))
                .toList();
        }

        if (mode == ViewMode.INVITED) {
            return repository.listInvitedWorlds(playerName)
                .stream()
                .filter(entry -> !isTicketWorld(entry))
                .toList();
        }

        if (mode == ViewMode.ARCHIVED) {
            return admin ? repository.listArchivedWorlds() : List.of();
        }

        if (mode == ViewMode.PUBLIC) {
            Map<String, WorldEntry> ticketWorlds = new ConcurrentHashMap<>();
            for (WorldEntry entry : repository.listOpenTicketWorlds()) {
                if (!isTicketWorld(entry)) {
                    continue;
                }
                boolean assignedToPlayer = containsIgnoreCase(entry.invitedPlayers(), playerName)
                    || containsIgnoreCase(entry.customers(), playerName);
                if (admin || assignedToPlayer) {
                    ticketWorlds.put(entry.worldName().toLowerCase(Locale.ROOT), entry);
                }
            }

            List<WorldEntry> tickets = new ArrayList<>(ticketWorlds.values());
            tickets.sort(Comparator.comparing(WorldEntry::worldName, String.CASE_INSENSITIVE_ORDER));
            return tickets;
        }

        Map<String, WorldEntry> ticketWorlds = new ConcurrentHashMap<>();
        for (WorldEntry entry : repository.listOwnWorlds(ownerUuid)) {
            if (isTicketWorld(entry)) {
                ticketWorlds.put(entry.worldName().toLowerCase(Locale.ROOT), entry);
            }
        }

        List<WorldEntry> discoverable = repository.listDiscoverableWorlds(ownerUuid, playerName, admin);
        for (WorldEntry entry : discoverable) {
            if (isTicketWorld(entry)) {
                ticketWorlds.putIfAbsent(entry.worldName().toLowerCase(Locale.ROOT), entry);
            }
        }

        List<WorldEntry> tickets = new ArrayList<>(ticketWorlds.values());
        tickets.sort(Comparator.comparing(WorldEntry::worldName, String.CASE_INSENSITIVE_ORDER));
        return tickets;
    }

    private boolean isTicketWorld(WorldEntry entry) {
        String sourceType = entry.sourceType();
        if (sourceType != null && sourceType.equalsIgnoreCase("ticket")) {
            return true;
        }
        if (entry.ticketOrderId() != null && !entry.ticketOrderId().isBlank()) {
            return true;
        }
        return entry.orderLabel() != null
            && ORDER_LABEL_PATTERN.matcher(entry.orderLabel().trim().toUpperCase(Locale.ROOT)).matches();
    }

    private void createWorld(Player player) {
        String worldName = generateLocalWorldName(player);
        createWorld(player, worldName, null, false, "private", null, List.of(), false, false, true);
    }

    private void createWorld(Player player, String worldName, String orderLabel) {
        createWorld(player, worldName, orderLabel, false, orderLabel == null ? "private" : "ticket", orderLabel, List.of(), false, false, true);
    }

    private void createWorld(
        Player player,
        String worldName,
        String orderLabel,
        boolean isPublic,
        String sourceType,
        String ticketOrderId,
        List<String> customers
    ) {
        createWorld(player, worldName, orderLabel, isPublic, sourceType, ticketOrderId, customers, false, false, true);
    }

    private void createWorld(
        Player player,
        String worldName,
        String orderLabel,
        boolean isPublic,
        String sourceType,
        String ticketOrderId,
        List<String> customers,
        boolean voidWorld,
        boolean chunkyEnabled,
        boolean notifyWhenVisible
    ) {
        createWorld(player, worldName, orderLabel, isPublic, sourceType, ticketOrderId, customers, voidWorld, chunkyEnabled, notifyWhenVisible, null);
    }

    /**
     * @param requestedServer explizit gewünschter Ziel-Server (z.B. aus dem guis.yml create-world/select-server
     *                        Wizard); wenn null/leer, wird der Ziel-Server wie bisher automatisch bestimmt.
     */
    private void createWorld(
        Player player,
        String worldName,
        String orderLabel,
        boolean isPublic,
        String sourceType,
        String ticketOrderId,
        List<String> customers,
        boolean voidWorld,
        boolean chunkyEnabled,
        boolean notifyWhenVisible,
        String requestedServer
    ) {
        int nextIndex = resolveMetadataWorldIndex(player, worldName);
        String targetServer = requestedServer != null && !requestedServer.isBlank()
            ? requestedServer.trim()
            : resolveTargetCreationServer();
        if (isBlockedServerName(targetServer)) {
            send(player, "create.blocked-server", "%server%", targetServer);
            return;
        }

        String localServer = resolveLocalServerId();
        boolean shouldCreateLocally = targetServer.isBlank()
            || localServer.isBlank()
            || isSameServerIdentifier(targetServer, localServer);

        if (!shouldCreateLocally) {
            send(player, "create.remote-info-1");
            send(player, "create.remote-info-2", "%server%", targetServer);
            String creatingTitle = configuredMessageLine("create-creating-title", "Welt wird erstellt...");
            player.sendTitle(ChatColor.translateAlternateColorCodes('&', creatingTitle), "", 10, 70, 10);

            persistWorldMetadataWithRetry(
                player.getUniqueId(),
                player.getName(),
                worldName,
                orderLabel,
                ticketOrderId,
                sourceType,
                customers,
                nextIndex,
                isPublic,
                3,
                false,
                notifyWhenVisible,
                targetServer
            );

            if (notifyWhenVisible) {
                send(player, "create.dashboard-visible-connect");
            }
            return;
        }

        WorldCreator creator = buildWorldCreator(worldName, voidWorld);

        World created = Bukkit.createWorld(creator);
        if (created == null) {
            send(player, "create-failed", "%world%", worldName);
            return;
        }

        applyWorldGuardProtection(created, player.getUniqueId().toString(), player.getName(), List.of(), List.of());
        runPostCreateCommands(created);

        ConsoleCommandSender console = Bukkit.getConsoleSender();
        Bukkit.dispatchCommand(console, "mv import " + worldName + " normal");

        // Title sofort zeigen
        String creatingTitle = configuredMessageLine("create-creating-title", "Welt wird erstellt...");
        player.sendTitle(ChatColor.translateAlternateColorCodes('&', creatingTitle), "", 10, 70, 10);

        new BukkitRunnable() {
            @Override
            public void run() {
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    send(player, "create-failed", "%world%", worldName);
                    return;
                }

                applyWorldGuardProtection(world, player.getUniqueId().toString(), player.getName(), List.of(), List.of());
                runPostCreateCommands(world);

                if (voidWorld) {
                    prepareVoidSpawn(world);
                }

                runEntityCleanup(world);
                if (chunkyEnabled) {
                    configureChunkyWorld(world, player);
                }

                applyWorldGuardProtection(world, player.getUniqueId().toString(), player.getName(), List.of(), List.of());

                persistWorldMetadataWithRetry(
                    player.getUniqueId(),
                    player.getName(),
                    worldName,
                    orderLabel,
                    ticketOrderId,
                    sourceType,
                    customers,
                    nextIndex,
                    isPublic,
                    3,
                    false,
                    notifyWhenVisible,
                    targetServer
                );

                send(player, "create-success", "%world%", worldName);
                if (orderLabel != null) {
                    send(player, "create.order-linked", "%order%", orderLabel);
                }
                if (voidWorld) {
                    send(player, "create.template-void");
                }
                if (chunkyEnabled) {
                    send(player, "create.chunky-started");
                }
                if (notifyWhenVisible) {
                    send(player, "create.dashboard-visible-teleport");
                    openGui(player, "my-worlds");
                    return;
                }

                scheduleCreatedWorldJoin(player, worldName, targetServer, 100L, false);
            }
        }.runTaskLater(plugin, 20L);
    }

    private String generateLocalWorldName(Player player) {
        for (int attempt = 0; attempt < 200; attempt++) {
            int localId = ThreadLocalRandom.current().nextInt(1000, 10_000);
            String worldName = player.getName() + "-" + localId;
            if (isLocalWorldNameAvailable(worldName)) {
                return worldName;
            }
        }

        int fallbackId = (int) ((System.currentTimeMillis() % 9000L) + 1000L);
        return player.getName() + "-" + fallbackId;
    }

    private boolean isLocalWorldNameAvailable(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return false;
        }
        if (Bukkit.getWorld(worldName) != null) {
            return false;
        }
        File worldFolder = new File(Bukkit.getWorldContainer(), worldName);
        return !worldFolder.exists();
    }

    private int resolveMetadataWorldIndex(Player player, String worldName) {
        Matcher matcher = GENERATED_WORLD_NAME_PATTERN.matcher(worldName == null ? "" : worldName);
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group(1));
        }
        return repository.nextWorldIndex(player.getUniqueId().toString());
    }

    private WorldCreator buildWorldCreator(String worldName, boolean voidWorld) {
        WorldCreator creator = new WorldCreator(worldName)
            .environment(World.Environment.NORMAL)
            .generateStructures(false);

        if (voidWorld) {
            creator.type(WorldType.NORMAL).generator("VoidGen");
            return creator;
        }

        creator
            .type(WorldType.FLAT)
            .generatorSettings(FLAT_WORLD_GENERATOR_SETTINGS);
        return creator;
    }

    private void configureChunkyWorld(World world, Player player) {
        if (Bukkit.getPluginManager().getPlugin("Chunky") == null) {
            plugin.getLogger().warning("Chunky wurde nicht gefunden. Vorgenerierung übersprungen für " + world.getName());
            send(player, "chunky.missing");
            return;
        }

        ConsoleCommandSender console = Bukkit.getConsoleSender();
        String worldName = world.getName();
        Bukkit.dispatchCommand(console, "chunky world " + worldName);
        Bukkit.dispatchCommand(console, "chunky center");
        Bukkit.dispatchCommand(console, "chunky shape circle");
        Bukkit.dispatchCommand(console, "chunky radius 2500");
        Bukkit.dispatchCommand(console, "chunky start");
    }

    private void prepareVoidSpawn(World world) {
        int spawnX = 0;
        int spawnY = 64;
        int spawnZ = 0;

        world.getBlockAt(spawnX, spawnY - 1, spawnZ).setType(Material.GRASS_BLOCK, false);
        world.getBlockAt(spawnX, spawnY, spawnZ).setType(Material.AIR, false);
        world.setSpawnLocation(spawnX, spawnY, spawnZ);
    }

    private void scheduleCreatedWorldJoin(Player player, String worldName, String targetServer, long delayTicks, boolean retryOnMissing) {
        scheduleCreatedWorldJoin(player, worldName, targetServer, delayTicks, 5);
    }

    private void scheduleCreatedWorldJoin(Player player, String worldName, String targetServer, long delayTicks, int retriesLeft) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player onlinePlayer = Bukkit.getPlayer(player.getUniqueId());
            if (onlinePlayer == null || !onlinePlayer.isOnline()) {
                return;
            }

            Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
            if (entryOpt.isEmpty()) {
                if (retriesLeft > 0) {
                    scheduleCreatedWorldJoin(onlinePlayer, worldName, targetServer, 20L, retriesLeft - 1);
                    return;
                }
                send(onlinePlayer, "create.pending-not-ready");
                return;
            }

            WorldEntry entry = entryOpt.get();
            if (isWorldOnThisServer(entry)) {
                if (joinCreatedWorldWhenReady(onlinePlayer, entry, true)) {
                    return;
                }

                if (retriesLeft > 0) {
                    scheduleCreatedWorldJoin(onlinePlayer, worldName, targetServer, 20L, retriesLeft - 1);
                    return;
                }

                send(onlinePlayer, "create.pending-load-timeout", "%world%", worldName);
                return;
            }

            sendWithPrefix(onlinePlayer, "create-joining-world", "Betrete Welt...");
            connectPlayerToWorldServer(onlinePlayer, entry);
            send(onlinePlayer, "create.pending-connect-target", "%server%", entry.serverName());
        }, delayTicks);
    }

    private void schedulePendingWorldTransferJoin(Player player, String worldName, long delayTicks, int retriesLeft) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player onlinePlayer = Bukkit.getPlayer(player.getUniqueId());
            if (onlinePlayer == null || !onlinePlayer.isOnline()) {
                return;
            }

            Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
            if (entryOpt.isEmpty()) {
                if (retriesLeft > 0) {
                    schedulePendingWorldTransferJoin(onlinePlayer, worldName, 20L, retriesLeft - 1);
                    return;
                }
                send(onlinePlayer, "transfer.pending-load-failed", "%world%", worldName);
                return;
            }

            WorldEntry entry = entryOpt.get();
            ensureWorldCreatedFromMetadata(entry);

            if (joinCreatedWorldWhenReady(onlinePlayer, entry, true)) {
                return;
            }

            if (retriesLeft > 0) {
                schedulePendingWorldTransferJoin(onlinePlayer, worldName, 20L, retriesLeft - 1);
                return;
            }

            send(onlinePlayer, "transfer.pending-load-failed", "%world%", worldName);
        }, delayTicks);
    }

    private boolean joinCreatedWorldWhenReady(Player player, WorldEntry entry, boolean notify) {
        String worldName = entry.worldName();
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv load " + worldName);
            world = Bukkit.getWorld(worldName);
        }
        if (world == null) {
            return false;
        }

        Location spawn = entry.toSpawnLocation(world).orElse(world.getSpawnLocation());
        player.teleport(spawn);
        applyPreferredGameMode(player, entry);
        if (notify) {
            send(player, "join-success", "%world%", worldName);
        }
        return true;
    }

    private void persistWorldMetadataWithRetry(
        UUID playerId,
        String ownerName,
        String worldName,
        String orderLabel,
        String ticketOrderId,
        String sourceType,
        List<String> customers,
        int worldIndex,
        boolean isPublic,
        int retriesLeft,
        boolean wasRetry,
        boolean notifyWhenVisible,
        String serverNameOverride
    ) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean persisted = repository.insertWorld(
                worldName,
                playerId.toString(),
                ownerName,
                orderLabel,
                ticketOrderId,
                sourceType,
                null,
                customers,
                worldIndex,
                worldName,
                Material.GRASS_BLOCK.name(),
                isPublic,
                false,
                serverNameOverride
            );

            if (persisted) {
                if (wasRetry || notifyWhenVisible) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player online = Bukkit.getPlayer(playerId);
                        if (online != null && online.isOnline()) {
                            if (notifyWhenVisible) {
                                send(online, "transfer.dashboard-visible", "%world%", worldName);
                                scheduleCreatedWorldJoin(online, worldName, serverNameOverride, 100L, true);
                            }
                            if (wasRetry) {
                                send(online, "transfer.dashboard-resynced");
                            }
                        }
                    });
                }
                return;
            }

            if (retriesLeft <= 0) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player online = Bukkit.getPlayer(playerId);
                    if (online != null && online.isOnline()) {
                        send(online, "transfer.api-unreachable");
                    }
                });
                return;
            }

            Bukkit.getScheduler().runTaskLater(plugin, () ->
                persistWorldMetadataWithRetry(
                    playerId,
                    ownerName,
                    worldName,
                    orderLabel,
                    ticketOrderId,
                    sourceType,
                    customers,
                    worldIndex,
                    isPublic,
                    retriesLeft - 1,
                    true,
                    notifyWhenVisible,
                    serverNameOverride
                ),
                20L * 5
            );
        });
    }

    private void runEntityCleanup(World world) {
        new BukkitRunnable() {
            int passes = 0;

            @Override
            public void run() {
                for (Entity entity : world.getEntities()) {
                    if (entity instanceof Player) {
                        continue;
                    }
                    entity.remove();
                }
                passes++;
                if (passes >= 3) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void deleteWorld(Player player, String worldName) {
        Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
        if (entryOpt.isEmpty()) {
            send(player, "world-not-found");
            return;
        }
        WorldEntry entry = entryOpt.get();
        if (!entry.ownerUuid().equals(player.getUniqueId().toString()) && !player.hasPermission(Permissions.ADMIN)) {
            send(player, "not-world-owner");
            return;
        }

        send(player, "delete-progress", "%world%", worldName);

        evacuatePlayersFromWorld(worldName);

        removeWorldGuardProtection(worldName);

        Plugin mvCore = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
        if (mvCore != null && mvCore.isEnabled()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv delete " + worldName);
        }

        World loaded = Bukkit.getWorld(worldName);
        if (loaded != null && !Bukkit.unloadWorld(loaded, false)) {
            send(player, "delete-unload-failed", "%world%", worldName);
            return;
        }

        File worldFolder = new File(Bukkit.getWorldContainer(), worldName);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (worldFolder.exists()) {
                try {
                    deleteWorldFolderRecursively(worldFolder.toPath());
                } catch (IOException ex) {
                    plugin.getLogger().warning("Weltordner konnte nicht gelöscht werden (" + worldName + "): " + ex.getMessage());
                }
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!isWorldReallyDeleted(worldName)) {
                    send(player, "delete-incomplete", "%world%", worldName);
                    plugin.getLogger().warning("Delete verification failed for world: " + worldName);
                    return;
                }

                cleanupMultiverseWorldEntry(worldName);
                repository.deleteWorld(worldName);
                send(player, "delete-success", "%world%", worldName);
            });
        });
    }

    private void archiveWorld(Player player, String worldName) {
        Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
        if (entryOpt.isEmpty()) {
            send(player, "world-not-found");
            return;
        }

        WorldEntry entry = entryOpt.get();
        if (!entry.ownerUuid().equals(player.getUniqueId().toString()) && !player.hasPermission(Permissions.ADMIN)) {
            send(player, "not-world-owner");
            return;
        }

        repository.archiveWorld(worldName);
        refreshWorldGuardProtection(worldName);
        send(player, "archive-success", "%world%", worldName);
    }

    private boolean isWorldReallyDeleted(String worldName) {
        if (Bukkit.getWorld(worldName) != null) {
            return false;
        }
        File worldFolder = new File(Bukkit.getWorldContainer(), worldName);
        return !worldFolder.exists();
    }

    private void cleanupMultiverseWorldEntry(String worldName) {
        Plugin mvCore = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
        if (mvCore == null) {
            return;
        }

        File worldsFile = new File(mvCore.getDataFolder(), "worlds.yml");
        if (!worldsFile.exists()) {
            return;
        }

        YamlConfiguration worldsConfig = YamlConfiguration.loadConfiguration(worldsFile);
        boolean changed = false;

        if (worldsConfig.contains(worldName)) {
            worldsConfig.set(worldName, null);
            changed = true;
        }

        for (String key : new ArrayList<>(worldsConfig.getKeys(false))) {
            if (key == null || key.equalsIgnoreCase(worldName)) {
                continue;
            }

            ConfigurationSection section = worldsConfig.getConfigurationSection(key);
            if (section == null) {
                continue;
            }

            String legacyWorldName = section.getString("read-only.legacy-world-name", "");
            String alias = section.getString("alias", "");
            if (worldName.equalsIgnoreCase(legacyWorldName) || worldName.equalsIgnoreCase(alias)) {
                worldsConfig.set(key, null);
                changed = true;
            }
        }

        if (!changed) {
            return;
        }

        try {
            worldsConfig.save(worldsFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Multiverse worlds.yml konnte für gelöschte Welt nicht bereinigt werden (" + worldName + "): " + ex.getMessage());
        }
    }

    private void deleteWorldFolderRecursively(Path worldPath) throws IOException {
        if (!Files.exists(worldPath)) {
            return;
        }
        try (var stream = Files.walk(worldPath)) {
            stream
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                });
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof IOException ioEx) {
                throw ioEx;
            }
            throw ex;
        }
    }

    private void refreshWorldGuardProtection(String worldName) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
            if (entryOpt.isEmpty()) {
                return;
            }

            WorldEntry entry = entryOpt.get();
            Bukkit.getScheduler().runTask(plugin, () -> {
                World world = Bukkit.getWorld(worldName);
                if (world != null) {
                    applyWorldGuardProtection(world, entry.ownerUuid(), entry.ownerName(), entry.invitedPlayers(), entry.trustedPlayers());
                }
            });
        });
    }

    private void removeWorldGuardProtection(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null || !isWorldGuardAvailable()) {
            return;
        }

        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        if (manager == null) {
            return;
        }

        manager.removeRegion(worldName);
        try {
            manager.save();
        } catch (StorageException ex) {
            plugin.getLogger().warning("WorldGuard-Region konnte nicht gelöscht werden für " + worldName + ": " + ex.getMessage());
        }
    }

    private void applyWorldGuardProtection(World world, String ownerUuid, String ownerName, List<String> invitedPlayers, List<String> trustedPlayers) {
        if (world == null || !isWorldGuardAvailable()) {
            return;
        }

        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        if (manager == null) {
            return;
        }

        BlockVector3 minimum = BlockVector3.at(-WORLD_BORDER_BLOCKS, world.getMinHeight(), -WORLD_BORDER_BLOCKS);
        BlockVector3 maximum = BlockVector3.at(WORLD_BORDER_BLOCKS, world.getMaxHeight(), WORLD_BORDER_BLOCKS);
        ProtectedRegion region = manager.getRegion(world.getName());
        if (region instanceof ProtectedCuboidRegion existingRegion) {
            region = existingRegion;
        } else {
            ProtectedCuboidRegion cuboidRegion = new ProtectedCuboidRegion(world.getName(), minimum, maximum);
            manager.addRegion(cuboidRegion);
            region = cuboidRegion;
        }

        region.setPriority(10);
        region.setOwners(new DefaultDomain());
        region.setMembers(new DefaultDomain());

        try {
            if (ownerUuid != null && !ownerUuid.isBlank()) {
                region.getOwners().addPlayer(UUID.fromString(ownerUuid.trim()));
            }
        } catch (IllegalArgumentException ignored) {
            if (ownerName != null && !ownerName.isBlank()) {
                region.getOwners().addPlayer(ownerName.trim());
            }
        }

        for (String trustedPlayer : trustedPlayers == null ? List.<String>of() : trustedPlayers) {
            addWorldGuardMember(region, trustedPlayer);
        }

        region.setFlag(Flags.BUILD, StateFlag.State.DENY);
        region.setFlag(Flags.BUILD.getRegionGroupFlag(), RegionGroup.NON_MEMBERS);
        region.setFlag(Flags.BLOCK_BREAK, StateFlag.State.DENY);
        region.setFlag(Flags.BLOCK_BREAK.getRegionGroupFlag(), RegionGroup.NON_MEMBERS);
        region.setFlag(Flags.BLOCK_PLACE, StateFlag.State.DENY);
        region.setFlag(Flags.BLOCK_PLACE.getRegionGroupFlag(), RegionGroup.NON_MEMBERS);
        region.setFlag(Flags.INTERACT, StateFlag.State.ALLOW);
        region.setFlag(Flags.INTERACT.getRegionGroupFlag(), RegionGroup.NON_MEMBERS);
        region.setFlag(Flags.USE, StateFlag.State.ALLOW);
        region.setFlag(Flags.USE.getRegionGroupFlag(), RegionGroup.NON_MEMBERS);

        try {
            manager.save();
        } catch (StorageException ex) {
            plugin.getLogger().warning("WorldGuard-Region konnte nicht gespeichert werden für " + world.getName() + ": " + ex.getMessage());
        }
    }

    private boolean isWorldGuardAvailable() {
        return Bukkit.getPluginManager().getPlugin("WorldGuard") != null;
    }

    private void addWorldGuardMember(ProtectedRegion region, String playerName) {
        if (region == null || playerName == null || playerName.isBlank()) {
            return;
        }

        String normalized = playerName.trim();
        Player online = Bukkit.getPlayerExact(normalized);
        if (online != null) {
            region.getMembers().addPlayer(online.getUniqueId());
            region.getMembers().addPlayer(online.getName());
            return;
        }

        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(normalized);
        if (cached != null && cached.getUniqueId() != null) {
            region.getMembers().addPlayer(cached.getUniqueId());
            String cachedName = cached.getName();
            if (cachedName != null && !cachedName.isBlank()) {
                region.getMembers().addPlayer(cachedName);
            }
            return;
        }

        region.getMembers().addPlayer(normalized);
    }

    private void evacuatePlayersFromWorld(String worldName) {
        World target = Bukkit.getWorld(worldName);
        if (target == null) {
            return;
        }

        List<Player> affectedPlayers = new ArrayList<>(target.getPlayers());
        for (Player affected : affectedPlayers) {
            World ownFallback = resolveNextOwnWorld(affected, worldName);
            if (ownFallback == null) {
                sendPlayerToLobby(affected, worldName);
                continue;
            }

            Location destination = ownFallback.getSpawnLocation();
            affected.teleport(destination);
            send(affected, "delete-evacuated-own-world", "%fallback_world%", ownFallback.getName());
        }
    }

    private void forcePlayerOutOfWorldIfNeeded(String playerName, String worldName) {
        if (playerName == null || playerName.isBlank() || worldName == null || worldName.isBlank()) {
            return;
        }

        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null || !target.isOnline()) {
            return;
        }

        World currentWorld = target.getWorld();
        if (currentWorld == null || !currentWorld.getName().equalsIgnoreCase(worldName)) {
            return;
        }

        World fallback = resolveNextOwnWorld(target, worldName);
        if (fallback == null) {
            sendPlayerToLobby(target, worldName);
            return;
        }

        target.teleport(fallback.getSpawnLocation());
        send(target, "delete-removed-from-world", "%world%", worldName, "%fallback_world%", fallback.getName());
    }

    private World resolveNextOwnWorld(Player player, String deletingWorldName) {
        List<WorldEntry> ownWorlds = repository.listOwnWorlds(player.getUniqueId().toString());
        for (WorldEntry entry : ownWorlds) {
            if (entry.worldName().equalsIgnoreCase(deletingWorldName)) {
                continue;
            }

            World candidate = Bukkit.getWorld(entry.worldName());
            if (candidate == null) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv load " + entry.worldName());
                candidate = Bukkit.getWorld(entry.worldName());
            }
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private void sendPlayerToLobby(Player player, String deletingWorldName) {
        String lobbyServer = resolveLobbyServerId();
        if (lobbyServer == null || lobbyServer.isBlank()) {
            player.kickPlayer(configuredMessageLine("delete-kick-reason", "Diese Welt wurde gelöscht."));
            return;
        }

        String controllerUrl = plugin.getConfig().getString("simplecloud.controller-url", "");
        String networkId = plugin.getConfig().getString("simplecloud.network-id", "");
        String networkSecret = plugin.getConfig().getString("simplecloud.network-secret", "");
        if (controllerUrl == null || controllerUrl.isBlank() || networkId == null || networkId.isBlank() || networkSecret == null || networkSecret.isBlank()) {
            player.kickPlayer(configuredMessageLine("delete-kick-reason", "Diese Welt wurde gelöscht."));
            return;
        }

        String normalizedController = controllerUrl.endsWith("/") ? controllerUrl.substring(0, controllerUrl.length() - 1) : controllerUrl;
        String playerId = URLEncoder.encode(player.getUniqueId().toString(), StandardCharsets.UTF_8);
        String endpoint = normalizedController + "/v0/players/connect?player_id=" + playerId;
        String payload = "{\"server_id\":\"" + lobbyServer + "\"}";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Accept", "*/*")
                    .header("X-Network-ID", networkId)
                    .header("X-Network-Secret", networkSecret)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

                HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player online = Bukkit.getPlayer(player.getUniqueId());
                    if (online == null || !online.isOnline()) {
                        return;
                    }

                    if (response.statusCode() / 100 == 2) {
                        send(online, "delete-lobby-transfer", "%world%", deletingWorldName);
                    } else {
                        online.kickPlayer(configuredMessageLine("delete-kick-reason", "Diese Welt wurde gelöscht."));
                    }
                });
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player online = Bukkit.getPlayer(player.getUniqueId());
                    if (online != null && online.isOnline()) {
                        online.kickPlayer(configuredMessageLine("delete-kick-reason", "Diese Welt wurde gelöscht."));
                    }
                });
            }
        });
    }

    private void setWorldPublic(Player player, String worldName, boolean isPublic, String permission) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, permission, true)) {
            return;
        }

        Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
        if (entryOpt.isEmpty()) {
            send(player, "world-not-found");
            return;
        }

        WorldEntry entry = entryOpt.get();
        if (!entry.ownerUuid().equals(player.getUniqueId().toString()) && !player.hasPermission(Permissions.ADMIN)) {
            send(player, "not-world-owner");
            return;
        }

        repository.setPublic(worldName, isPublic);
        send(player, isPublic ? "set-public" : "set-private");
    }

    private void setTicketWorldPublic(Player player, String orderLabel, boolean isPublic, String permission) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, permission, true)) {
            return;
        }

        String normalizedOrder = orderLabel == null ? "" : orderLabel.trim().toUpperCase(Locale.ROOT);
        if (!ORDER_LABEL_PATTERN.matcher(normalizedOrder).matches()) {
            send(player, "order.invalid-format");
            return;
        }

        Optional<WorldEntry> entryOpt = repository
            .listOwnWorlds(player.getUniqueId().toString())
            .stream()
            .filter(entry -> normalizedOrder.equalsIgnoreCase(entry.ticketOrderId()) || normalizedOrder.equalsIgnoreCase(entry.orderLabel()))
            .findFirst();

        if (entryOpt.isEmpty()) {
            send(player, "set-ticket.world-not-found-for-order", "%order%", normalizedOrder);
            return;
        }

        repository.setPublic(entryOpt.get().worldName(), isPublic);
        send(player, isPublic ? "set-public" : "set-private");
    }

    private World resolveOwnFallbackWorld(Player player, String deletingWorldName) {
        List<WorldEntry> ownWorlds = repository.listOwnWorlds(player.getUniqueId().toString());
        for (WorldEntry entry : ownWorlds) {
            String candidateName = entry.worldName();
            if (candidateName.equalsIgnoreCase(deletingWorldName)) {
                continue;
            }

            World candidate = Bukkit.getWorld(candidateName);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private boolean joinWorld(Player player, String worldName, boolean notify) {
        Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
        if (entryOpt.isEmpty()) {
            if (notify) {
                send(player, "world-not-found");
            }
            return false;
        }

        WorldEntry entry = entryOpt.get();
        boolean isOwner = entry.ownerUuid().equals(player.getUniqueId().toString());
        boolean invited = containsIgnoreCase(entry.invitedPlayers(), player.getName());
        boolean canAccess = isOwner || invited || entry.isPublic() || player.hasPermission(Permissions.ADMIN);
        if (!canAccess) {
            if (notify) {
                send(player, "world-private");
            }
            return false;
        }

        if (!isWorldOnThisServer(entry)) {
            if (notify) {
                send(player, "join.remote-world-connecting");
            }
            connectPlayerToWorldServer(player, entry);
            return true;
        }

        return joinWorldLocally(player, entry, notify);
    }

    private boolean joinWorldLocally(Player player, WorldEntry entry, boolean notify) {
        String worldName = entry.worldName();
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv load " + worldName);
            world = Bukkit.getWorld(worldName);
        }
        if (world == null) {
            if (notify) {
                send(player, "world-not-loaded");
            }
            return false;
        }

        Location spawn = entry.toSpawnLocation(world).orElse(world.getSpawnLocation());
        player.teleport(spawn);
        applyPreferredGameMode(player, entry);
        if (notify) {
            send(player, "join-success", "%world%", worldName);
        }
        return true;
    }

    private boolean isWorldOnThisServer(WorldEntry entry) {
        String worldServer = entry.serverName();
        if (worldServer == null || worldServer.isBlank()) {
            return true;
        }
        String localServer = resolveLocalServerId();
        if (localServer == null || localServer.isBlank()) {
            return false;
        }
        return isSameServerIdentifier(worldServer, localServer);
    }

    private boolean isSameServerIdentifier(String left, String right) {
        String a = normalizeServerIdentifier(left);
        String b = normalizeServerIdentifier(right);
        if (a.isBlank() || b.isBlank()) {
            return false;
        }
        if (a.equalsIgnoreCase(b)) {
            return true;
        }

        String aBase = stripInstanceSuffix(a);
        String bBase = stripInstanceSuffix(b);
        return !aBase.isBlank() && aBase.equalsIgnoreCase(bBase);
    }

    private String normalizeServerIdentifier(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private String stripInstanceSuffix(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceFirst("[-_](\\d+)$", "");
    }

    private String resolveLocalServerId() {
        String explicitServer = resolveExplicitLocalServerId();
        if (!explicitServer.isBlank()) {
            return explicitServer;
        }

        try {
            String host = InetAddress.getLocalHost().getHostName();
            if (host != null && !host.isBlank()) {
                return host.trim();
            }
        } catch (Exception ignored) {
            // Best-effort fallback only.
        }

        String bukkitName = Bukkit.getServer().getName();
        if (bukkitName != null && !bukkitName.isBlank()) {
            return bukkitName.trim() + "-" + Bukkit.getServer().getPort();
        }

        if (!warnedMissingLocalServerId) {
            warnedMissingLocalServerId = true;
            plugin.getLogger().warning(
                "Konnte keine lokale Server-ID aus Config/ENV/Hostname ermitteln. " +
                "Setze api.local-server-name oder SIMPLECLOUD_SERVICE_NAME/SIMPLECLOUD_SERVER_ID."
            );
        }
        return "";
    }

    private String resolveExplicitLocalServerId() {
        String configuredLocalServer = plugin.getConfig().getString("api.local-server-name", "");
        if (configuredLocalServer != null && !configuredLocalServer.isBlank()) {
            return configuredLocalServer.trim();
        }

        for (String envKey : List.of(
            "SIMPLECLOUD_SERVER_NAME",
            "SIMPLECLOUD_SERVER_ID",
            "SIMPLECLOUD_SERVICE_NAME",
            "SIMPLECLOUD_SERVICE_ID",
            "CLOUDNET_SERVICE_ID",
            "CLOUDNET_SERVICE_NAME"
        )) {
            String value = System.getenv(envKey);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    public String resolveLocalServerIdentifier() {
        return resolveLocalServerId();
    }

    private String resolveTargetCreationServer() {
        String localServer = resolveLocalServerId();
        List<String> blockedServers = resolveConfiguredBlockedServerNames();
        boolean localBlocked = !localServer.isBlank()
            && blockedServers.stream().anyMatch(blockedId -> isSameServerIdentifier(blockedId, localServer));

        List<String> selectableServers = resolveSelectableServerNames();
        if (selectableServers.isEmpty()) {
            return localServer == null ? "" : localServer;
        }

        if (localBlocked) {
            return localServer;
        }

        for (String selectable : selectableServers) {
            if (selectable.equalsIgnoreCase(localServer)) {
                return localServer;
            }
        }

        return selectableServers.get(0);
    }

    private List<String> resolveConfiguredBlockedServerNames() {
        List<String> configured = plugin.getConfig().getStringList("api.blocked-server-names");
        List<String> out = new ArrayList<>();
        for (String value : configured) {
            if (value == null) {
                continue;
            }
            String normalized = value.trim();
            if (!normalized.isBlank()) {
                out.add(normalized);
            }
        }
        return out;
    }

    private boolean isBlockedServerName(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return false;
        }
        List<String> blockedServers = resolveConfiguredBlockedServerNames();
        return blockedServers.stream().anyMatch(blockedId -> isSameServerIdentifier(blockedId, serverId));
    }

    private List<String> resolveSelectableServerNames() {
        List<String> blockedServers = resolveConfiguredBlockedServerNames();
        String localServer = resolveLocalServerId();

        boolean localBlocked = !localServer.isBlank()
            && blockedServers.stream().anyMatch(blockedId -> isSameServerIdentifier(blockedId, localServer));

        List<String> apiOnline = repository.listOnlineServerNames(20);
        if (!apiOnline.isEmpty()) {
            LinkedHashSet<String> merged = new LinkedHashSet<>(apiOnline);
            if (!localServer.isBlank() && !localBlocked) {
                merged.add(localServer);
            }

            List<String> allOnline = new ArrayList<>(merged);
            allOnline.sort(String.CASE_INSENSITIVE_ORDER);

            if (blockedServers.isEmpty()) {
                return allOnline;
            }

            List<String> visible = new ArrayList<>();
            for (String online : allOnline) {
                boolean blocked = blockedServers.stream().anyMatch(blockedId -> isSameServerIdentifier(blockedId, online));
                if (!blocked) {
                    visible.add(online);
                }
            }
            if (!visible.isEmpty()) {
                return visible;
            }
        }

        Optional<Set<String>> onlineIdentifiersOpt = fetchOnlineServerIdentifiers();

        if (onlineIdentifiersOpt.isPresent()) {
            LinkedHashSet<String> merged = new LinkedHashSet<>(onlineIdentifiersOpt.get());
            if (!localServer.isBlank() && !localBlocked) {
                merged.add(localServer);
            }

            List<String> allOnline = new ArrayList<>(merged);
            allOnline.sort(String.CASE_INSENSITIVE_ORDER);

            if (!allOnline.isEmpty() && blockedServers.isEmpty()) {
                return allOnline;
            }

            List<String> visible = new ArrayList<>();
            for (String online : allOnline) {
                boolean blocked = blockedServers.stream().anyMatch(blockedId -> isSameServerIdentifier(blockedId, online));
                if (!blocked) {
                    visible.add(online);
                }
            }
            if (!visible.isEmpty()) {
                return visible;
            }
        }

        return resolveFallbackServerNames(blockedServers);
    }

    private List<String> resolveFallbackServerNames(List<String> blockedServers) {
        String local = resolveLocalServerId();
        if (local == null || local.isBlank()) {
            return List.of();
        }

        boolean localBlocked = blockedServers.stream().anyMatch(blockedId -> isSameServerIdentifier(blockedId, local));
        return localBlocked ? List.of() : List.of(local);
    }

    private Optional<Set<String>> fetchOnlineServerIdentifiers() {
        String controllerUrl = plugin.getConfig().getString("simplecloud.controller-url", "");
        String networkId = plugin.getConfig().getString("simplecloud.network-id", "");
        String networkSecret = plugin.getConfig().getString("simplecloud.network-secret", "");
        if (controllerUrl == null || controllerUrl.isBlank() || networkId == null || networkId.isBlank() || networkSecret == null || networkSecret.isBlank()) {
            return Optional.empty();
        }

        String normalizedController = controllerUrl.endsWith("/") ? controllerUrl.substring(0, controllerUrl.length() - 1) : controllerUrl;
        // Prefer the dedicated servers endpoint first (works more reliably on platform controller).
        for (String path : List.of("/v0/servers?sort_order=asc", "/v0/servers", "/v0/services")) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalizedController + path))
                    .header("Accept", "application/json")
                    .header("X-Network-ID", networkId)
                    .header("X-Network-Secret", networkSecret)
                    .GET()
                    .build();

                HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    continue;
                }

                Set<String> identifiers = parseOnlineServerIdentifiers(response.body());
                return Optional.of(identifiers);
            } catch (Exception ignored) {
                // Try next known endpoint.
            }
        }

        return Optional.empty();
    }

    private Set<String> parseOnlineServerIdentifiers(String json) {
        Set<String> out = new LinkedHashSet<>();
        if (json == null || json.isBlank()) {
            return out;
        }

        Pattern objectPattern = Pattern.compile("\\{[^{}]*}");
        Matcher objectMatcher = objectPattern.matcher(json);
        while (objectMatcher.find()) {
            String objectJson = objectMatcher.group();
            if (!looksLikeOnlineService(objectJson)) {
                continue;
            }

            String identifier = firstNonBlank(
                extractJsonString(objectJson, "server_id"),
                extractJsonString(objectJson, "service_id"),
                extractJsonString(objectJson, "service_name"),
                extractJsonString(objectJson, "server_name"),
                extractJsonString(objectJson, "name"),
                extractJsonString(objectJson, "id")
            );

            if (identifier != null && !identifier.isBlank()) {
                out.add(identifier.trim());
            }
        }
        return out;
    }

    private boolean looksLikeOnlineService(String objectJson) {
        if (objectJson == null || objectJson.isBlank()) {
            return false;
        }

        if (jsonBooleanFieldIsTrue(objectJson, "online") || jsonBooleanFieldIsTrue(objectJson, "running")) {
            return true;
        }

        String state = firstNonBlank(
            extractJsonString(objectJson, "state"),
            extractJsonString(objectJson, "status")
        );
        if (state == null) {
            return false;
        }

        String normalized = state.trim().toUpperCase(Locale.ROOT);
        return "ONLINE".equals(normalized)
            || "RUNNING".equals(normalized)
            || "STARTED".equals(normalized)
            || "AVAILABLE".equals(normalized);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** Öffentlicher Wrapper für Tab-Completion (z.B. /nav create &lt;world-type&gt; &lt;server-id&gt;). */
    public List<String> listAllowedServerNames() {
        return resolveSelectableServerNames();
    }

    private void connectPlayerToWorldServer(Player player, WorldEntry entry) {
        String targetServer = entry.serverName();
        if (targetServer == null || targetServer.isBlank()) {
            send(player, "connect.target-server-missing");
            return;
        }

        String controllerUrl = plugin.getConfig().getString("simplecloud.controller-url", "");
        String networkId = plugin.getConfig().getString("simplecloud.network-id", "");
        String networkSecret = plugin.getConfig().getString("simplecloud.network-secret", "");
        if (controllerUrl == null || controllerUrl.isBlank() || networkId == null || networkId.isBlank() || networkSecret == null || networkSecret.isBlank()) {
            send(player, "connect.simplecloud-not-configured");
            return;
        }

        String normalizedController = controllerUrl.endsWith("/") ? controllerUrl.substring(0, controllerUrl.length() - 1) : controllerUrl;
        String playerId = URLEncoder.encode(player.getUniqueId().toString(), StandardCharsets.UTF_8);
        String endpoint = normalizedController + "/v0/players/connect?player_id=" + playerId;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            repository.upsertPendingWorldTransfer(player.getName(), entry.worldName());

            String message;
            boolean alreadyConnected = false;
            String effectiveTargetServer = resolveEffectiveConnectTargetServer(targetServer);
            String payload = "{\"server_id\":\"" + effectiveTargetServer + "\"}";
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Accept", "*/*")
                    .header("X-Network-ID", networkId)
                    .header("X-Network-Secret", networkSecret)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

                HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body() == null ? "" : response.body();
                if (response.statusCode() / 100 == 2 && jsonBooleanFieldIsTrue(body, "success")) {
                    if (!effectiveTargetServer.equalsIgnoreCase(targetServer)) {
                        message = configuredMessageLine(
                            "connect.offline-fallback",
                            "&eZielserver &f%requested_server% &eist offline. Verbinde stattdessen zu &f%effective_server%&e ...",
                            "%requested_server%",
                            targetServer,
                            "%effective_server%",
                            effectiveTargetServer
                        );
                    } else {
                        message = configuredMessageLine("connect.success", "&aDu wirst auf &f%server% &averbunden ...", "%server%", effectiveTargetServer);
                    }
                } else {
                    repository.upsertPendingWorldTransfer(player.getName(), null);
                    String detail = extractJsonString(body, "message");
                    if (detail == null || detail.isBlank()) {
                        detail = extractJsonString(body, "error");
                    }
                    if (detail == null || detail.isBlank()) {
                        detail = "Transfer fehlgeschlagen (HTTP " + response.statusCode() + ")";
                    }
                    String normalizedDetail = detail.toLowerCase(Locale.ROOT);
                    alreadyConnected = normalizedDetail.contains("already connected")
                        && normalizedDetail.contains("this server");
                    if (alreadyConnected) {
                        message = configuredMessageLine("connect.already-on-server", "&7Du bist bereits auf diesem Server. Lokaler Welten-Join wird versucht ...");
                    } else {
                        message = configuredMessageLine("connect.failed", "&cServer-Wechsel fehlgeschlagen: &f%error%", "%error%", detail);
                    }
                }
            } catch (Exception ex) {
                repository.upsertPendingWorldTransfer(player.getName(), null);
                message = configuredMessageLine("connect.failed", "&cServer-Wechsel fehlgeschlagen: &f%error%", "%error%", ex.getMessage());
            }

            String finalMessage = message;
            boolean shouldTryLocalJoin = alreadyConnected;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.sendMessage(finalMessage);
                    if (shouldTryLocalJoin) {
                        joinWorldLocally(player, entry, true);
                    }
                }
            });
        });
    }

    private String resolveEffectiveConnectTargetServer(String requestedServer) {
        String normalizedRequested = normalizeServerIdentifier(requestedServer);
        if (normalizedRequested.isBlank()) {
            return requestedServer;
        }

        List<String> onlineServers = repository.listOnlineServerNames(20);
        if (onlineServers.isEmpty()) {
            return requestedServer;
        }

        for (String online : onlineServers) {
            if (online != null && online.equalsIgnoreCase(normalizedRequested)) {
                return online;
            }
        }

        String requestedBase = stripInstanceSuffix(normalizedRequested);
        if (requestedBase.isBlank()) {
            return requestedServer;
        }

        List<String> siblings = new ArrayList<>();
        for (String online : onlineServers) {
            if (online == null || online.isBlank()) {
                continue;
            }
            String onlineBase = stripInstanceSuffix(normalizeServerIdentifier(online));
            if (!onlineBase.isBlank() && onlineBase.equalsIgnoreCase(requestedBase)) {
                siblings.add(online);
            }
        }

        if (siblings.isEmpty()) {
            return requestedServer;
        }

        siblings.sort(String.CASE_INSENSITIVE_ORDER);
        return siblings.get(0);
    }

    private String resolveLobbyServerId() {
        String configuredLobbyServer = plugin.getConfig().getString("simplecloud.lobby-server-id", "lobby-1");
        if (configuredLobbyServer == null || configuredLobbyServer.isBlank()) {
            return "lobby-1";
        }
        return configuredLobbyServer.trim();
    }

    private void applyPreferredGameMode(Player player, WorldEntry entry) {
        GameMode preferred = resolvePreferredGameMode(player, entry);
        player.setGameMode(preferred);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.setGameMode(preferred);
            }
        }, 1L);
    }

    private GameMode resolvePreferredGameMode(Player player, WorldEntry entry) {
        String configuredMode = worldDefaultsConfig.getString("world-defaults.game-mode", "CREATIVE");
        if (configuredMode == null || configuredMode.isBlank()) {
            return GameMode.CREATIVE;
        }

        try {
            return GameMode.valueOf(configuredMode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Ungültiger world-defaults.game-mode: " + configuredMode + " (Fallback: CREATIVE)");
            return GameMode.CREATIVE;
        }
    }

    public void applyWorldDefaultsOnEnter(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        if (!isManagedWorld(player.getWorld())) {
            return;
        }

        if (worldDefaultsConfig.getBoolean("world-defaults.apply-on-join", true)) {
            applyPreferredGameMode(player, null);
        }

        if (shouldKeepVitalFull(player)) {
            restorePlayerVitals(player);
        }
    }

    public boolean shouldKeepVitalFull(Player player) {
        if (player == null) {
            return false;
        }

        boolean loseVital = worldDefaultsConfig.getBoolean("world-defaults.lost-vital", true);
        if (loseVital) {
            return false;
        }

        boolean managedOnly = worldDefaultsConfig.getBoolean("world-defaults.lost-vital-managed-worlds-only", true);
        return !managedOnly || isManagedWorld(player.getWorld());
    }

    public void restorePlayerVitals(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        double maxHealth = 20.0D;
        if (player.getAttribute(Attribute.MAX_HEALTH) != null) {
            maxHealth = player.getAttribute(Attribute.MAX_HEALTH).getValue();
        }
        player.setHealth(Math.max(1.0D, maxHealth));
        player.setFoodLevel(20);
        player.setSaturation(20.0F);
        player.setExhaustion(0.0F);
        if (player.getFireTicks() > 0) {
            player.setFireTicks(0);
        }
    }

    private void runPostCreateCommands(World world) {
        if (world == null) {
            return;
        }

        applyConfiguredWorldGameRules(world);
        applyConfiguredWorldGuardFlags(world);

        // Optional: legacy/custom free-form commands after structured sections.
        List<String> commands = worldDefaultsConfig.getStringList("world-defaults.post-create-commands");
        if (commands == null || commands.isEmpty()) {
            return;
        }

        String worldName = world.getName();
        String worldLower = worldName.toLowerCase(Locale.ROOT);
        for (String raw : commands) {
            if (raw == null || raw.isBlank()) {
                continue;
            }

            String resolved = raw
                .replace("%world%", worldName)
                .replace("%world_lower%", worldLower)
                .replace("%region%", worldName)
                .replace("%region_lower%", worldLower)
                .trim();

            if (resolved.startsWith("/")) {
                resolved = resolved.substring(1);
            }
            if (resolved.isBlank()) {
                continue;
            }

            if (tryApplyGameRuleEntry(world, resolved)) {
                continue;
            }

            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
            if (!ok) {
                plugin.getLogger().warning("Post-Create-Command fehlgeschlagen: " + resolved);
            }
        }
    }

    private void applyConfiguredWorldGameRules(World world) {
        if (world == null) {
            return;
        }

        List<String> entries = worldDefaultsConfig.getStringList("world-defaults.gamerules");
        if (entries == null || entries.isEmpty()) {
            return;
        }

        for (String raw : entries) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            if (!tryApplyGameRuleEntry(world, raw)) {
                plugin.getLogger().warning("Ungültiger gamerules-Eintrag in world-defaults.yml: " + raw);
            }
        }
    }

    private void applyConfiguredWorldGuardFlags(World world) {
        if (world == null) {
            return;
        }

        ConfigurationSection flagsSection = worldDefaultsConfig.getConfigurationSection("world-defaults.worldguard.flags");
        if (flagsSection == null) {
            return;
        }

        String worldName = world.getName();
        String worldLower = worldName.toLowerCase(Locale.ROOT);
        for (String flagName : flagsSection.getKeys(false)) {
            if (flagName == null || flagName.isBlank()) {
                continue;
            }

            Object rawValueObj = flagsSection.get(flagName);
            if (rawValueObj == null) {
                continue;
            }

            String rawValue = String.valueOf(rawValueObj).trim();
            if (rawValue.isBlank()) {
                continue;
            }

            String resolvedFlag = flagName
                .replace("%world%", worldName)
                .replace("%world_lower%", worldLower)
                .replace("%region%", worldName)
                .replace("%region_lower%", worldLower)
                .trim();

            String resolvedValue = rawValue
                .replace("%world%", worldName)
                .replace("%world_lower%", worldLower)
                .replace("%region%", worldName)
                .replace("%region_lower%", worldLower)
                .trim();

            if (resolvedFlag.isBlank() || resolvedValue.isBlank()) {
                continue;
            }

            String command = "rg flag -w " + worldName + " " + worldName + " " + resolvedFlag + " " + quoteIfNeeded(resolvedValue);
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            if (!ok) {
                plugin.getLogger().warning("WorldGuard-Flag konnte nicht gesetzt werden: " + command);
            }
        }
    }

    private String quoteIfNeeded(String value) {
        if (value == null) {
            return "";
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "\"\"";
        }

        if (trimmed.contains(" ") && !(trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            return "\"" + trimmed.replace("\"", "\\\"") + "\"";
        }
        return trimmed;
    }

    private boolean tryApplyGameRuleEntry(World world, String rawEntry) {
        if (world == null || rawEntry == null) {
            return false;
        }

        String trimmed = rawEntry.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return false;
        }

        String normalized = trimmed;
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1).trim();
        }
        if (normalized.regionMatches(true, 0, "gamerule ", 0, "gamerule ".length())) {
            normalized = normalized.substring("gamerule ".length()).trim();
        }

        String[] parts = normalized.split("\\s+");
        if (parts.length < 2) {
            return false;
        }

        String rawRuleName = parts[0].trim();
        String rawValue = parts[1].trim();
        if (rawRuleName.isBlank() || rawValue.isBlank()) {
            return false;
        }

        if (!rawValue.equalsIgnoreCase("true") && !rawValue.equalsIgnoreCase("false")) {
            return false;
        }

        boolean value = Boolean.parseBoolean(rawValue);
        String normalizedRule = normalizeLegacyGameRuleName(rawRuleName);
        setAnyGameRule(world, value, normalizedRule, rawRuleName);
        return true;
    }

    private boolean isManagedWorld(World world) {
        if (world == null || !isWorldGuardAvailable()) {
            return false;
        }

        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        if (manager == null) {
            return false;
        }

        return manager.getRegion(world.getName()) != null;
    }

    private String resolveContextWorld(Player player) {
        String selected = selectedWorldByPlayer.get(player.getUniqueId());
        if (selected != null && !selected.isBlank()) {
            return selected;
        }

        String current = player.getWorld().getName();
        Optional<WorldEntry> entry = repository.findByWorldName(current);
        if (entry.isPresent() && entry.get().ownerUuid().equals(player.getUniqueId().toString())) {
            return current;
        }
        return null;
    }

    private ItemStack worldIcon(WorldEntry entry, ViewMode mode, String currentWorldName) {
        Material material = Material.matchMaterial(entry.iconMaterial());
        if (material == null || !material.isItem()) {
            material = Material.GRASS_BLOCK;
        }

        boolean isCurrentWorld = currentWorldName != null && currentWorldName.equalsIgnoreCase(entry.worldName());
        boolean hasSettingsRights = mode == ViewMode.OWN;

        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("%displayname%", entry.displayName());
        placeholders.put("%owner_typ%", isTicketWorld(entry) ? "Kunde" : "Owner");
        placeholders.put("%player%", entry.ownerName() == null ? "" : entry.ownerName());
        placeholders.put("%server%", entry.serverName() == null ? "" : entry.serverName());
        placeholders.put("%status%", hoverStatusText(entry));
        placeholders.put("%right_click%", hasSettingsRights ? "&r&7<Rechtsklick> &bEinstellungen" : "");

        String titleTemplate = plugin.getConfig().getString("hoover-dialog.title", "&b%displayname%");
        String title = ChatColor.translateAlternateColorCodes('&', applyPlaceholders(titleTemplate, placeholders));

        List<String> lineTemplates = plugin.getConfig().getStringList("hoover-dialog.lines");
        List<Component> lore = new ArrayList<>();
        for (String lineTemplate : lineTemplates) {
            String resolved = applyPlaceholders(lineTemplate, placeholders);
            if (lineTemplate.contains("%right_click%") && resolved.isBlank()) {
                continue;
            }
            lore.add(legacyLine(resolved));
        }

        return namedWorldItem(material, title, entry.worldName(), lore, isCurrentWorld);
    }

    private String hoverStatusText(WorldEntry entry) {
        if (entry.isArchived()) {
            return "Archiviert";
        }
        return entry.isPublic() ? "Öffentlich" : "Privat";
    }

    private String applyPlaceholders(String template, Map<String, String> placeholders) {
        String result = template == null ? "" : template;
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            result = result.replace(placeholder.getKey(), placeholder.getValue());
        }
        return result;
    }

    private Component legacyLine(String line) {
        return legacy(ChatColor.translateAlternateColorCodes('&', line));
    }

    private Component titleForMain(ViewMode mode, int page) {
        String type;
        if (mode == ViewMode.OWN) {
            type = "Meine Welten";
        } else if (mode == ViewMode.INVITED) {
            type = "Eingeladene Welten";
        } else if (mode == ViewMode.ARCHIVED) {
            type = "Archivierte Tickets";
        } else {
            type = "Tickets";
        }
        return Component.text(type + " - Seite " + (page + 1), NamedTextColor.DARK_AQUA);
    }

    private String extractJsonString(String json, String key) {
        if (json == null || json.isBlank()) {
            return null;
        }
        String marker = "\"" + key + "\"";
        int markerIndex = json.indexOf(marker);
        if (markerIndex < 0) {
            return null;
        }
        int colonIndex = json.indexOf(':', markerIndex + marker.length());
        if (colonIndex < 0) {
            return null;
        }
        int firstQuote = json.indexOf('"', colonIndex + 1);
        if (firstQuote < 0) {
            return null;
        }
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) {
            return null;
        }
        return json.substring(firstQuote + 1, secondQuote);
    }

    private boolean jsonBooleanFieldIsTrue(String json, String key) {
        if (json == null || json.isBlank() || key == null || key.isBlank()) {
            return false;
        }

        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(json);
        return matcher.find() && "true".equalsIgnoreCase(matcher.group(1));
    }

    private ItemStack namedItem(Material material, String name) {
        return namedItem(material, name, List.of());
    }

    private ItemStack namedItem(Material material, String name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(legacy(name));
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack namedWorldItem(Material material, String name, String worldName, List<Component> lore, boolean glowing) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(legacy(name));
        meta.lore(lore);
        if (glowing) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        meta.getPersistentDataContainer().set(worldKey, PersistentDataType.STRING, worldName);
        item.setItemMeta(meta);
        return item;
    }

    private Component legacy(String input) {
        return LegacyComponentSerializer.legacySection().deserialize(input);
    }

    private boolean hasPermission(Player player, String permission, boolean notify) {
        if (player.hasPermission(permission)) {
            return true;
        }
        if (notify) {
            send(player, "no-permission");
        }
        return false;
    }

    public void sendWithPrefix(org.bukkit.command.CommandSender sender, String key, String fallbackLiteral, String... replacements) {
        sendConfigured(sender, true, key, fallbackLiteral, replacements);
    }

    public void sendNoPrefixConfigured(org.bukkit.command.CommandSender sender, String key, String fallbackLiteral, String... replacements) {
        sendConfigured(sender, false, key, fallbackLiteral, replacements);
    }

    private void send(Player player, String key, String... replacements) {
        sendConfigured(player, true, key, key, replacements);
    }

    private void sendNoPrefix(Player player, String key, String... replacements) {
        sendConfigured(player, false, key, key, replacements);
    }

    private String configuredMessageLine(String key, String fallbackLiteral, String... replacements) {
        MessageStyle style = resolveMessageStyle(key, true);
        List<String> lines = readConfiguredMessageLines(key, style, fallbackLiteral, replacements);
        if (lines.isEmpty()) {
            return fallbackLiteral;
        }
        return lines.get(0);
    }

    private boolean isChatFeedbackSuppressed(Player player) {
        return player != null && suppressedChatFeedbackPlayers.contains(player.getUniqueId());
    }

    private void sendPlain(Player player, String message) {
        // chat-feedback=false should hide success output, but still show errors/warnings.
        if (isChatFeedbackSuppressed(player) && message != null && message.startsWith("§a")) {
            return;
        }
        player.sendMessage(message);
    }

    private void sendPlainConfigured(Player player, String key, String fallbackLiteral, String... replacements) {
        List<String> lines = readConfiguredMessageLines(key, MessageStyle.NO_PREFIX, fallbackLiteral, replacements);
        for (String line : lines) {
            sendPlain(player, ChatColor.translateAlternateColorCodes('&', line));
        }
    }

    private void sendConfigured(org.bukkit.command.CommandSender sender, boolean defaultWithPrefix, String key, String fallbackLiteral, String... replacements) {
        if (sender == null) {
            return;
        }

        MessageStyle style = resolveMessageStyle(key, defaultWithPrefix);
        List<String> lines = readConfiguredMessageLines(key, style, fallbackLiteral, replacements);
        String prefixTemplate = readMessageString("prefix", "&3WorldsGUI &8» &7%messages%");

        for (String line : lines) {
            String output = style.withPrefix
                ? prefixTemplate.replace("%messages%", line)
                : line;
            sender.sendMessage(parseFormattedMessage(output));
        }
    }

    private MessageStyle resolveMessageStyle(String key, boolean defaultWithPrefix) {
        String withPrefixPath = "with-prefix." + key;
        String noPrefixPath = "no-prefix." + key;
        boolean hasWithPrefix = hasMessagePath(withPrefixPath);
        boolean hasNoPrefix = hasMessagePath(noPrefixPath);

        if (hasWithPrefix && !hasNoPrefix) {
            return MessageStyle.WITH_PREFIX;
        }
        if (hasNoPrefix && !hasWithPrefix) {
            return MessageStyle.NO_PREFIX;
        }
        return defaultWithPrefix ? MessageStyle.WITH_PREFIX : MessageStyle.NO_PREFIX;
    }

    private boolean hasMessagePath(String path) {
        return containsMessagePath(path);
    }

    private List<String> readConfiguredMessageLines(String key, MessageStyle style, String fallbackLiteral, String... replacements) {
        List<String> lines = new ArrayList<>();

        if (style.withPrefix) {
            lines = readMessageLinesFromPath("with-prefix." + key);
            if (lines.isEmpty()) {
                lines = readMessageLinesFromPath(key);
            }
        } else {
            lines = readMessageLinesFromPath("no-prefix." + key);
            if (lines.isEmpty()) {
                lines = readMessageLinesFromPath(key);
            }
        }

        if (lines.isEmpty()) {
            lines.add(fallbackLiteral == null || fallbackLiteral.isBlank() ? key : fallbackLiteral);
        }

        return applyMessageReplacements(lines, replacements);
    }

    @SuppressWarnings("unchecked")
    private List<String> readMessageLinesFromPath(String path) {
        if (!containsMessagePath(path)) {
            return new ArrayList<>();
        }

        Object value = readMessageValue(path);
        List<String> lines = new ArrayList<>();

        if (value instanceof List<?> listValue) {
            for (Object raw : listValue) {
                if (raw != null) {
                    lines.add(raw.toString());
                }
            }
            return lines;
        }

        if (value instanceof String text) {
            lines.add(text);
        }
        return lines;
    }

    private boolean containsMessagePath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }

        if (messagesConfig != null && (messagesConfig.contains(path) || messagesConfig.contains("messages." + path))) {
            return true;
        }

        return plugin.getConfig().contains("messages." + path) || plugin.getConfig().contains(path);
    }

    private String readMessageString(String path, String fallback) {
        Object value = readMessageValue(path);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return fallback;
    }

    private Object readMessageValue(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        Object value = messagesConfig == null ? null : messagesConfig.get(path);
        if (value == null && messagesConfig != null) {
            value = messagesConfig.get("messages." + path);
        }
        if (value != null) {
            return value;
        }

        value = plugin.getConfig().get("messages." + path);
        if (value != null) {
            return value;
        }
        return plugin.getConfig().get(path);
    }

    private List<String> applyMessageReplacements(List<String> lines, String... replacements) {
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            String formatted = line;
            for (int i = 0; i + 1 < replacements.length; i += 2) {
                formatted = formatted.replace(replacements[i], replacements[i + 1]);
            }
            out.add(formatted);
        }
        return out;
    }

    private List<String> readMessageLines(String path, String fallbackKey, String... replacements) {
        return readMessageLines(path, "messages." + fallbackKey, fallbackKey, replacements);
    }

    @SuppressWarnings("unchecked")
    private List<String> readMessageLines(String path, String fallbackPath, String fallbackLiteral, String... replacements) {
        Object value = plugin.getConfig().get(path);
        if (value == null) {
            value = plugin.getConfig().get(fallbackPath);
        }

        List<String> lines = new ArrayList<>();
        if (value instanceof List<?> listValue) {
            for (Object raw : listValue) {
                if (raw != null) {
                    lines.add(raw.toString());
                }
            }
        } else if (value instanceof String raw) {
            lines.add(raw);
        }

        if (lines.isEmpty()) {
            lines.add(fallbackLiteral);
        }

        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            String formatted = line;
            for (int i = 0; i + 1 < replacements.length; i += 2) {
                formatted = formatted.replace(replacements[i], replacements[i + 1]);
            }
            out.add(formatted);
        }
        return out;
    }

    private enum MessageStyle {
        WITH_PREFIX(true),
        NO_PREFIX(false);

        private final boolean withPrefix;

        MessageStyle(boolean withPrefix) {
            this.withPrefix = withPrefix;
        }
    }

    private Component parseFormattedMessage(String input) {
        String mini = legacyToMiniMessage(input);
        return MiniMessage.miniMessage().deserialize(mini);
    }

    private String legacyToMiniMessage(String input) {
        Matcher matcher = LEGACY_CODE_PATTERN.matcher(input);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            char code = Character.toLowerCase(matcher.group(1).charAt(0));
            String replacement = LEGACY_TO_MINI.getOrDefault(code, "");
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String normalizePlayerName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isBlank() || trimmed.length() > 16) {
            return null;
        }
        if (!trimmed.matches("^[A-Za-z0-9_]{2,16}$")) {
            return null;
        }
        return trimmed;
    }

    private boolean containsIgnoreCase(List<String> values, String needle) {
        for (String value : values) {
            if (value.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean removeIgnoreCase(List<String> values, String needle) {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).equalsIgnoreCase(needle)) {
                values.remove(i);
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void setAnyGameRule(World world, boolean value, String... names) {
        for (String name : names) {
            GameRule<?> rule = GameRule.getByName(name);
            if (rule instanceof GameRule<?> genericRule) {
                world.setGameRule((GameRule<Boolean>) genericRule, value);
                return;
            }
        }
    }

    private String normalizeLegacyGameRuleName(String rawRuleName) {
        if (rawRuleName == null) {
            return "";
        }

        String normalized = rawRuleName.trim();
        String key = normalized.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "advance_time", "daylight_cycle" -> "doDaylightCycle";
            case "advance_weather", "weather_cycle" -> "doWeatherCycle";
            case "spawn_mobs" -> "doMobSpawning";
            case "fall_damage" -> "fallDamage";
            case "keep_inventory" -> "keepInventory";
            default -> normalized;
        };
    }

    private void openIconSelectorMenu(Player player, String worldName, int returnPage) {
        int page = 0;
        openIconSelectorMenuPage(player, worldName, returnPage, page);
    }

    private void openIconSelectorMenuPage(Player player, String worldName, int returnPage, int page) {
        Inventory inv = Bukkit.createInventory(
            new IconSelectorHolder(worldName, page, returnPage),
            54,
            Component.text("Icon auswählen - Seite " + (page + 1), NamedTextColor.DARK_AQUA)
        );

        List<Material> itemMaterials = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material.isItem() && material != Material.AIR) {
                itemMaterials.add(material);
            }
        }

        int start = page * 45;
        for (int i = 0; i < 45; i++) {
            int idx = start + i;
            if (idx >= itemMaterials.size()) {
                break;
            }
            Material material = itemMaterials.get(idx);
            inv.setItem(i, namedItem(material, "§b" + material.name()));
        }

        if (page > 0) {
            inv.setItem(45, namedItem(Material.ARROW, "§fZurück"));
        }

        int maxPage = Math.max(0, (int) Math.ceil(itemMaterials.size() / 45.0) - 1);
        if (page < maxPage) {
            inv.setItem(53, namedItem(Material.ARROW, "§fWeiter"));
        }

        inv.setItem(49, namedItem(Material.SPRUCE_DOOR, "§fZurück zu Settings"));

        player.openInventory(inv);
    }

    private void handleIconSelectorClick(Player player, InventoryClickEvent event, IconSelectorHolder holder) {
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }

        if (slot == 45) {
            if (holder.page() > 0) {
                openIconSelectorMenuPage(player, holder.worldName(), holder.returnPage(), holder.page() - 1);
            }
            return;
        }

        if (slot == 53) {
            List<Material> itemMaterials = new ArrayList<>();
            for (Material material : Material.values()) {
                if (material.isItem() && material != Material.AIR) {
                    itemMaterials.add(material);
                }
            }

            int maxPage = Math.max(0, (int) Math.ceil(itemMaterials.size() / 45.0) - 1);
            if (holder.page() < maxPage) {
                openIconSelectorMenuPage(player, holder.worldName(), holder.returnPage(), holder.page() + 1);
            }
            return;
        }

        if (slot == 49) {
            openEditWorldGui(player, holder.worldName());
            return;
        }

        if (slot > 44) {
            return;
        }

        Material selected = clicked.getType();
        repository.setIcon(holder.worldName(), selected.name());
        send(player, "icon-success", "%icon%", selected.name());
        openEditWorldGui(player, holder.worldName());
    }

    private record PendingDeleteConfirmation(String worldName, int code, long expiresAtEpochMs, boolean archiveOnly) {
    }

    private record PendingTicketWorld(String worldName, List<String> customers, boolean archived) {
    }

    private record CloneTarget(String ownerUuid, String ownerName) {
    }
}
