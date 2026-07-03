package net.clanimg.worldsGUI.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.clanimg.worldsGUI.Permissions;
import net.clanimg.worldsGUI.WorldsGUI;
import net.clanimg.worldsGUI.data.WorldsRepository;
import net.clanimg.worldsGUI.model.WorldEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

public final class GuiManager {
    private static final int MAIN_SIZE = 54;
    private static final int SETTINGS_SIZE = 36;
    private static final int PAGE_SIZE = 36;

    private final WorldsGUI plugin;
    private final WorldsRepository repository;
    private final NamespacedKey worldKey;

    private final Map<UUID, String> selectedWorldByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, PendingInput> pendingInputs = new ConcurrentHashMap<>();

    public GuiManager(WorldsGUI plugin, WorldsRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        this.worldKey = new NamespacedKey(plugin, "world_name");
    }

    public void shutdown() {
        selectedWorldByPlayer.clear();
        pendingInputs.clear();
    }

    public void executeNav(Player player) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV, true)) {
            return;
        }
        openMainMenu(player, ViewMode.OWN, 0);
    }

    public void executeSetSpawn(Player player) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.SETSPAWN, true)) {
            return;
        }
        handleSetSpawn(player);
    }

    public void executeRename(Player player, String[] args) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.RENAME, true)) {
            return;
        }
        handleRenameCommand(player, args);
    }

    public void executeIcon(Player player, String[] args) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.ICON, true)) {
            return;
        }
        handleIconCommand(player, args);
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

        if (holder instanceof SettingsHolder settingsHolder) {
            event.setCancelled(true);
            handleSettingsClick(player, event, settingsHolder);
            return;
        }

        if (holder instanceof IconSelectorHolder selectorHolder) {
            event.setCancelled(true);
            handleIconSelectorClick(player, event, selectorHolder);
        }
    }

    public void handleChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
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
            openSettingsMenu(player, pending.worldName(), 0);
        });
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
            List<WorldEntry> worlds = listWorldsFor(player, holder.mode());
            int maxPage = Math.max(0, (int) Math.ceil(worlds.size() / (double) PAGE_SIZE) - 1);
            if (holder.page() < maxPage) {
                openMainMenu(player, holder.mode(), holder.page() + 1);
            }
            return;
        }

        if (slot == 49) {
            createWorld(player);
            return;
        }

        if (slot == 50) {
            ViewMode nextMode = holder.mode() == ViewMode.OWN ? ViewMode.PUBLIC : ViewMode.OWN;
            openMainMenu(player, nextMode, 0);
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
            joinWorld(player, worldName);
            return;
        }

        if (event.isRightClick() && holder.mode() == ViewMode.OWN) {
            Optional<WorldEntry> entry = repository.findByWorldName(worldName);
            if (entry.isPresent() && entry.get().ownerUuid().equals(player.getUniqueId().toString())) {
                selectedWorldByPlayer.put(player.getUniqueId(), worldName);
                openSettingsMenu(player, worldName, holder.page());
            }
        }
    }

    private void handleSettingsClick(Player player, InventoryClickEvent event, SettingsHolder holder) {
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SETTINGS_SIZE) {
            return;
        }

        String worldName = holder.worldName();
        Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
        if (entryOpt.isEmpty()) {
            send(player, "world-not-found");
            player.closeInventory();
            return;
        }

        WorldEntry entry = entryOpt.get();
        if (!entry.ownerUuid().equals(player.getUniqueId().toString())) {
            send(player, "not-world-owner");
            player.closeInventory();
            return;
        }

        selectedWorldByPlayer.put(player.getUniqueId(), worldName);

        switch (slot) {
            case 27 -> openMainMenu(player, ViewMode.OWN, holder.returnPage());
            case 10 -> {
                repository.setPublic(worldName, !entry.isPublic());
                openSettingsMenu(player, worldName, holder.returnPage());
                send(player, !entry.isPublic() ? "set-public" : "set-private");
            }
            case 12 -> {
                if (!hasPermission(player, Permissions.RENAME, true)) {
                    return;
                }
                pendingInputs.put(player.getUniqueId(), new PendingInput(PendingType.RENAME, worldName));
                player.closeInventory();
                send(player, "rename-prompt");
            }
            case 13 -> {
                if (!hasPermission(player, Permissions.ICON, true)) {
                    return;
                }
                openIconSelectorMenu(player, worldName, holder.returnPage());
            }
            case 14 -> {
                if (!event.isShiftClick()) {
                    send(player, "delete-confirm");
                    return;
                }
                deleteWorld(player, worldName);
                openMainMenu(player, ViewMode.OWN, 0);
            }
            case 16 -> send(player, "setspawn-help");
            default -> {
            }
        }
    }

    private void handleSetSpawn(Player player) {
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
        String worldName = resolveContextWorld(player);
        if (worldName == null) {
            send(player, "no-selected-world");
            return;
        }

        if (args.length == 0) {
            pendingInputs.put(player.getUniqueId(), new PendingInput(PendingType.RENAME, worldName));
            send(player, "rename-prompt");
            return;
        }

        String newName = String.join(" ", args).trim();
        applyRename(player, worldName, newName);
    }

    private void handleIconCommand(Player player, String[] args) {
        String worldName = resolveContextWorld(player);
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
        repository.setDisplayName(worldName, newDisplayName);
        send(player, "rename-success", "%name%", newDisplayName);
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
        Inventory inv = Bukkit.createInventory(new MainHolder(mode, page), MAIN_SIZE, titleForMain(mode, page));

        ItemStack filler = namedItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 36; slot <= 44; slot++) {
            inv.setItem(slot, filler);
        }

        List<WorldEntry> worlds = listWorldsFor(player, mode);
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx >= worlds.size()) {
                break;
            }
            WorldEntry entry = worlds.get(idx);
            inv.setItem(i, worldIcon(entry, mode));
        }

        if (page > 0) {
            inv.setItem(45, namedItem(Material.ARROW, "§fZurück"));
        }

        int maxPage = Math.max(0, (int) Math.ceil(worlds.size() / (double) PAGE_SIZE) - 1);
        if (page < maxPage) {
            inv.setItem(53, namedItem(Material.ARROW, "§fWeiter"));
        }

        inv.setItem(49, namedItem(Material.EMERALD_BLOCK, "§aNeue Welt erstellen"));
        if (mode == ViewMode.OWN) {
            inv.setItem(50, namedItem(Material.COMPASS, "§bÖffentliche Welten ansehen"));
        } else {
            inv.setItem(50, namedItem(Material.COMPASS, "§eMeine Welten ansehen"));
        }

        player.openInventory(inv);
    }

    private void openSettingsMenu(Player player, String worldName, int returnPage) {
        Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
        if (entryOpt.isEmpty()) {
            send(player, "world-not-found");
            openMainMenu(player, ViewMode.OWN, 0);
            return;
        }
        WorldEntry entry = entryOpt.get();

        Inventory inv = Bukkit.createInventory(
            new SettingsHolder(worldName, returnPage),
            SETTINGS_SIZE,
            Component.text("Welt-Settings: " + entry.displayName(), NamedTextColor.DARK_AQUA)
        );

        ItemStack filler = namedItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 28; slot <= 35; slot++) {
            inv.setItem(slot, filler);
        }
        inv.setItem(27, namedItem(Material.SPRUCE_DOOR, "§fZurück"));

        inv.setItem(
            10,
            namedItem(
                entry.isPublic() ? Material.LIME_DYE : Material.GRAY_DYE,
                entry.isPublic() ? "§aÖffentlich" : "§7Privat",
                List.of(Component.text("Klicke zum Umschalten"))
            )
        );
        inv.setItem(12, namedItem(Material.NAME_TAG, "§eAnzeigename ändern", List.of(Component.text("Klicke oder nutze /rename"))));
        inv.setItem(13, namedItem(Material.ITEM_FRAME, "§bIcon ändern", List.of(Component.text("Klicke oder nutze /icon <MATERIAL>"))));
        inv.setItem(14, namedItem(Material.BARRIER, "§cWelt löschen", List.of(Component.text("Shift-Klick zum Löschen mit Multiverse"))));
        inv.setItem(16, namedItem(Material.COMPASS, "§fSpawn setzen", List.of(Component.text("Nutze /setspawn in dieser Welt"))));

        player.openInventory(inv);
    }

    private List<WorldEntry> listWorldsFor(Player player, ViewMode mode) {
        if (mode == ViewMode.OWN) {
            return repository.listOwnWorlds(player.getUniqueId().toString());
        }

        boolean admin = player.hasPermission(Permissions.ADMIN);
        return repository.listDiscoverableWorlds(player.getUniqueId().toString(), admin);
    }

    private void createWorld(Player player) {
        int nextIndex = repository.nextWorldIndex(player.getUniqueId().toString());
        String worldName = player.getName() + "-" + nextIndex;
        String cmd = "mv create " + worldName + " normal -t flat";

        ConsoleCommandSender console = Bukkit.getConsoleSender();
        boolean dispatched = Bukkit.dispatchCommand(console, cmd);
        if (!dispatched) {
            send(player, "create-failed", "%world%", worldName);
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    send(player, "create-failed", "%world%", worldName);
                    return;
                }

                setAnyGameRule(world, false, "spawn_mobs", "doMobSpawning");
                setAnyGameRule(world, false, "advance_weather", "weather_cycle", "doWeatherCycle");
                setAnyGameRule(world, false, "advance_time", "daylight_cycle", "doDaylightCycle");

                runEntityCleanup(world);

                repository.insertWorld(
                    worldName,
                    player.getUniqueId().toString(),
                    player.getName(),
                    nextIndex,
                    worldName,
                    Material.GRASS_BLOCK.name(),
                    true
                );

                send(player, "create-success", "%world%", worldName);
                openMainMenu(player, ViewMode.OWN, 0);
            }
        }.runTaskLater(plugin, 20L);
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

        boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv delete " + worldName);
        if (!ok) {
            send(player, "delete-failed");
            return;
        }

        repository.deleteWorld(worldName);
        send(player, "delete-success", "%world%", worldName);
    }

    private void joinWorld(Player player, String worldName) {
        Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
        if (entryOpt.isEmpty()) {
            send(player, "world-not-found");
            return;
        }

        WorldEntry entry = entryOpt.get();
        boolean isOwner = entry.ownerUuid().equals(player.getUniqueId().toString());
        boolean canAccess = isOwner || entry.isPublic() || player.hasPermission(Permissions.ADMIN);
        if (!canAccess) {
            send(player, "world-private");
            return;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv load " + worldName);
            world = Bukkit.getWorld(worldName);
        }
        if (world == null) {
            send(player, "world-not-loaded");
            return;
        }

        Location spawn = entry.toSpawnLocation(world).orElse(world.getSpawnLocation());
        player.teleport(spawn);
        send(player, "join-success", "%world%", worldName);
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

    private ItemStack worldIcon(WorldEntry entry, ViewMode mode) {
        Material material = Material.matchMaterial(entry.iconMaterial());
        if (material == null || !material.isItem()) {
            material = Material.GRASS_BLOCK;
        }

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Owner: " + entry.ownerName(), NamedTextColor.GRAY));
        lore.add(Component.text(entry.isPublic() ? "Status: Öffentlich" : "Status: Privat", entry.isPublic() ? NamedTextColor.GREEN : NamedTextColor.RED));
        lore.add(Component.empty());
        lore.add(Component.text("Linksklick: Beitreten", NamedTextColor.YELLOW));
        if (mode == ViewMode.OWN) {
            lore.add(Component.text("Rechtsklick: Einstellungen", NamedTextColor.YELLOW));
        }

        return namedWorldItem(material, "§b" + entry.displayName(), entry.worldName(), lore);
    }

    private Component titleForMain(ViewMode mode, int page) {
        String type = mode == ViewMode.OWN ? "Meine Welten" : "Öffentliche Welten";
        return Component.text(type + " - Seite " + (page + 1), NamedTextColor.DARK_AQUA);
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

    private ItemStack namedWorldItem(Material material, String name, String worldName, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(legacy(name));
        meta.lore(lore);
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

    private void send(Player player, String key, String... replacements) {
        String raw = plugin.getConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            raw = raw.replace(replacements[i], replacements[i + 1]);
        }
        String prefixTemplate = plugin.getConfig().getString("messages.prefix", "&3WorldsGUI &8» &7%messages%");
        String full = prefixTemplate.replace("%messages%", raw).replace('&', '§');
        player.sendMessage(full);
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
            openSettingsMenu(player, holder.worldName(), holder.returnPage());
            return;
        }

        if (slot > 44) {
            return;
        }

        Material selected = clicked.getType();
        repository.setIcon(holder.worldName(), selected.name());
        send(player, "icon-success", "%icon%", selected.name());
        openSettingsMenu(player, holder.worldName(), holder.returnPage());
    }
}
