package net.clanimg.worldsGUI.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
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
    private static final Pattern WORLD_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-]{3,32}$");
    private static final Pattern ORDER_LABEL_PATTERN = Pattern.compile("^A\\d{3}$");

    private final WorldsGUI plugin;
    private final WorldsRepository repository;
    private final NamespacedKey worldKey;

    private final Map<UUID, String> selectedWorldByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, PendingInput> pendingInputs = new ConcurrentHashMap<>();
    private final Map<UUID, PendingDeleteConfirmation> pendingDeleteByPlayer = new ConcurrentHashMap<>();

    public GuiManager(WorldsGUI plugin, WorldsRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        this.worldKey = new NamespacedKey(plugin, "world_name");
    }

    public void shutdown() {
        selectedWorldByPlayer.clear();
        pendingInputs.clear();
        pendingDeleteByPlayer.clear();
    }

    public void executeNav(Player player) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV, true)) {
            return;
        }
        openMainMenu(player, ViewMode.OWN, 0);
        syncAssignedTicketWorlds(player);
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
            player.sendMessage("§cUngültige Auftragsnummer. Format: A010");
            return;
        }

        WorldsRepository.OrderAssignmentCheck orderCheck = repository.checkOrderAssignment(normalizedOrder, player.getName());
        if (!orderCheck.exists()) {
            player.sendMessage("§cDieser Auftrag existiert nicht: §f" + normalizedOrder);
            return;
        }

        if (!player.hasPermission(Permissions.ADMIN) && !orderCheck.assigned()) {
            player.sendMessage("§cDu bist diesem Auftrag nicht zugewiesen.");
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

    public void executeVerify(Player player, String codeRaw) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.VERIFY, true)) {
            return;
        }

        String code = codeRaw == null ? "" : codeRaw.trim();
        if (!code.matches("\\d{4}")) {
            player.sendMessage("§cUsage: /verify <4-digit-code>");
            return;
        }

        String baseUrl = resolveProfileApiBaseUrl();
        String apiToken = resolveProfileApiToken();
        if (baseUrl == null || baseUrl.isBlank() || apiToken == null || apiToken.isBlank()) {
            player.sendMessage("§cVerify ist nicht konfiguriert (api.base-url/api.profile-token bzw. api.token in config.yml).");
            return;
        }

        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String endpoint = normalizedBase + "/auth/profile/minecraft/verify/confirm-server";
        String payload = "{\"mcName\":\"" + player.getName() + "\",\"code\":\"" + code + "\",\"playerName\":\"" + player.getName() + "\"}";

        player.sendMessage("§7Prüfe Verify-Code ...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String message;
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("X-API-Token", apiToken)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

                HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body() == null ? "" : response.body();

                if (response.statusCode() / 100 == 2 && jsonBooleanFieldIsTrue(body, "verified")) {
                    message = "§aMinecraft-Profil erfolgreich verifiziert.";
                } else {
                    String error = extractJsonString(body, "error");
                    if (error == null || error.isBlank()) {
                        error = "Code ungültig oder abgelaufen.";
                    }
                    message = "§cVerify fehlgeschlagen: §f" + error;
                }
            } catch (Exception ex) {
                message = "§cVerify fehlgeschlagen: §f" + ex.getMessage();
            }

            String finalMessage = message;
            Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(finalMessage));
        });
    }

    public void executeUnverify(Player player) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.UNVERIFY, true)) {
            return;
        }

        String baseUrl = resolveProfileApiBaseUrl();
        String apiToken = resolveProfileApiToken();
        if (baseUrl == null || baseUrl.isBlank() || apiToken == null || apiToken.isBlank()) {
            player.sendMessage("§cUnverify ist nicht konfiguriert (api.base-url/api.profile-token bzw. api.token in config.yml).");
            return;
        }

        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String endpoint = normalizedBase + "/auth/profile/minecraft/verify/unverify-server";
        String payload = "{\"mcName\":\"" + player.getName() + "\",\"playerName\":\"" + player.getName() + "\"}";

        player.sendMessage("§7Entferne Verifizierung ...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String message;
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("X-API-Token", apiToken)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

                HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body() == null ? "" : response.body();

                if (response.statusCode() / 100 == 2 && jsonBooleanFieldIsTrue(body, "ok")) {
                    message = "§aMinecraft-Verifizierung wurde entfernt.";
                } else {
                    String error = extractJsonString(body, "error");
                    if (error == null || error.isBlank()) {
                        error = "Unverify fehlgeschlagen.";
                    }
                    message = "§cUnverify fehlgeschlagen: §f" + error;
                }
            } catch (Exception ex) {
                message = "§cUnverify fehlgeschlagen: §f" + ex.getMessage();
            }

            String finalMessage = message;
            Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(finalMessage));
        });
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

    public void executeNavSetSpawn(Player player, String worldName) {
        if (!hasPermission(player, Permissions.USE, true)) {
            return;
        }
        if (!player.hasPermission(Permissions.NAV_SETSPAWN) && !hasPermission(player, Permissions.SETSPAWN, true)) {
            return;
        }
        handleSetSpawn(player, worldName);
    }

    public void executeNavMyWorldCreate(Player player, String worldName) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_MY_WORLD_CREATE, true)) {
            return;
        }

        String normalizedWorld = worldName == null ? "" : worldName.trim();
        if (!WORLD_NAME_PATTERN.matcher(normalizedWorld).matches()) {
            player.sendMessage("§cUngültiger Weltname. Erlaubt: 3-32 Zeichen (A-Z, 0-9, _, -)");
            return;
        }

        if (repository.findByWorldName(normalizedWorld).isPresent()) {
            player.sendMessage("§cDiese Welt existiert bereits.");
            return;
        }

        createWorld(player, normalizedWorld, null, false, "private", null, List.of());
    }

    public void executeNavMyWorldDelete(Player player, String worldName, boolean confirmed) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_MY_WORLD_DELETE, true)) {
            return;
        }
        if (!confirmed) {
            player.sendMessage("§eNutze: /nav my-world delete <world-name> confirm");
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
            player.sendMessage("§cUngültiger Spielername.");
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
            player.sendMessage("§cSpieler ist nicht eingeladen und kann nicht getrusted werden.");
            return;
        }

        List<String> trusted = new ArrayList<>(entry.trustedPlayers());
        if (containsIgnoreCase(trusted, normalizedTarget)) {
            player.sendMessage("§eSpieler ist bereits getrusted.");
            return;
        }

        trusted.add(normalizedTarget);
        repository.setTrustedPlayers(worldName, trusted);
        player.sendMessage("§aSpieler §f" + normalizedTarget + " §ahat jetzt Baurechte in §f" + worldName + "§a.");
    }

    public void executeNavMyWorldUntrust(Player player, String worldName, String targetPlayer) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_MY_WORLD_UNTRUST, true)) {
            return;
        }

        String normalizedTarget = normalizePlayerName(targetPlayer);
        if (normalizedTarget == null) {
            player.sendMessage("§cUngültiger Spielername.");
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
            player.sendMessage("§eSpieler hat aktuell keinen Trust-Status.");
            return;
        }

        repository.setTrustedPlayers(worldName, trusted);
        player.sendMessage("§aTrust für §f" + normalizedTarget + " §awurde entfernt.");
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

    public void executeNavCreate(Player player, String worldName, String orderLabel) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_CREATE, true)) {
            return;
        }

        String normalizedWorld = worldName.trim();
        if (!WORLD_NAME_PATTERN.matcher(normalizedWorld).matches()) {
            player.sendMessage("§cUngültiger Weltname. Erlaubt: 3-32 Zeichen (A-Z, 0-9, _, -)");
            return;
        }

        String normalizedOrder = orderLabel.trim().toUpperCase(Locale.ROOT);
        if (!ORDER_LABEL_PATTERN.matcher(normalizedOrder).matches()) {
            player.sendMessage("§cUngültige Auftragsnummer. Format: A010");
            return;
        }

        if (repository.findByWorldName(normalizedWorld).isPresent()) {
            player.sendMessage("§cDiese Welt existiert bereits.");
            return;
        }

        createWorld(player, normalizedWorld, normalizedOrder, false, "ticket", normalizedOrder, List.of());
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
        PendingDeleteConfirmation pending = new PendingDeleteConfirmation(worldName, confirmCode, expiresAt);
        pendingDeleteByPlayer.put(player.getUniqueId(), pending);

        player.sendMessage("§eArchivierung bestätigen mit: §f/nav confirm " + confirmCode + " §7(innerhalb 30 Sekunden)");

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingDeleteConfirmation current = pendingDeleteByPlayer.get(player.getUniqueId());
            if (current != null && current.code() == confirmCode) {
                pendingDeleteByPlayer.remove(player.getUniqueId());
                player.sendMessage("§cLöschbestätigung ist abgelaufen.");
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
            player.sendMessage("§cKeine offene Löschbestätigung vorhanden.");
            return;
        }

        int code;
        try {
            code = Integer.parseInt(codeRaw);
        } catch (NumberFormatException ex) {
            player.sendMessage("§cBestätigungscode muss numerisch sein.");
            return;
        }

        if (pending.expiresAtEpochMs() < System.currentTimeMillis()) {
            pendingDeleteByPlayer.remove(player.getUniqueId());
            player.sendMessage("§cLöschbestätigung ist abgelaufen.");
            return;
        }

        if (pending.code() != code) {
            player.sendMessage("§cBestätigungscode ist ungültig.");
            return;
        }

        pendingDeleteByPlayer.remove(player.getUniqueId());
        deleteWorld(player, pending.worldName());
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

    public void executeNavCustomerInvite(Player player, String worldName, String targetPlayer) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_INVITE, true)) {
            return;
        }

        String normalizedTarget = normalizePlayerName(targetPlayer);
        if (normalizedTarget == null) {
            player.sendMessage("§cUngültiger Spielername.");
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
            player.sendMessage("§eSpieler ist bereits eingeladen.");
            return;
        }

        invited.add(normalizedTarget);
        repository.setInvitedPlayers(worldName, invited);
        player.sendMessage("§aSpieler §f" + normalizedTarget + " §awurde für §f" + worldName + " §aeingeladen.");
    }

    public void executeNavCustomerRemove(Player player, String worldName, String targetPlayer) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_REMOVE, true)) {
            return;
        }

        String normalizedTarget = normalizePlayerName(targetPlayer);
        if (normalizedTarget == null) {
            player.sendMessage("§cUngültiger Spielername.");
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
            player.sendMessage("§eSpieler ist für diese Welt nicht eingeladen.");
            return;
        }

        List<String> trusted = new ArrayList<>(entry.trustedPlayers());
        removeIgnoreCase(trusted, normalizedTarget);

        repository.setInvitedPlayers(worldName, invited);
        repository.setTrustedPlayers(worldName, trusted);
        player.sendMessage("§aEinladung für §f" + normalizedTarget + " §awurde entfernt.");
    }

    public void executeNavCustomerTrust(Player player, String targetPlayer) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_TRUST, true)) {
            return;
        }

        String normalizedTarget = normalizePlayerName(targetPlayer);
        if (normalizedTarget == null) {
            player.sendMessage("§cUngültiger Spielername.");
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
            player.sendMessage("§cSpieler ist nicht eingeladen und kann nicht getrusted werden.");
            return;
        }

        List<String> trusted = new ArrayList<>(entry.trustedPlayers());
        if (containsIgnoreCase(trusted, normalizedTarget)) {
            player.sendMessage("§eSpieler ist bereits getrusted.");
            return;
        }

        trusted.add(normalizedTarget);
        repository.setTrustedPlayers(worldName, trusted);
        player.sendMessage("§aSpieler §f" + normalizedTarget + " §ahat jetzt Baurechte in §f" + worldName + "§a.");
    }

    public void executeNavCustomerUntrust(Player player, String targetPlayer) {
        if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_UNTRUST, true)) {
            return;
        }

        String normalizedTarget = normalizePlayerName(targetPlayer);
        if (normalizedTarget == null) {
            player.sendMessage("§cUngültiger Spielername.");
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
            player.sendMessage("§eSpieler hat aktuell keinen Trust-Status.");
            return;
        }

        repository.setTrustedPlayers(worldName, trusted);
        player.sendMessage("§aTrust für §f" + normalizedTarget + " §awurde entfernt.");
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
            openMainMenu(player, holder.mode(), holder.page() + 1);
            return;
        }

        if (slot == 49) {
            if (holder.mode() != ViewMode.OWN) {
                return;
            }
            createWorld(player);
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
            pendingInputs.put(player.getUniqueId(), new PendingInput(PendingType.RENAME, worldName));
            send(player, "rename-prompt");
            return;
        }

        String newName = String.join(" ", args).trim();
        applyRename(player, worldName, newName);
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
        UUID playerId = player.getUniqueId();
        String playerUuid = playerId.toString();
        String playerName = player.getName();
        boolean admin = player.hasPermission(Permissions.ADMIN);

        player.openInventory(buildMainMenu(player.hasPermission(Permissions.ADMIN), mode, page, List.of(), true));

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

                onlinePlayer.openInventory(buildMainMenu(onlinePlayer.hasPermission(Permissions.ADMIN), mode, page, worlds, false));
            });
        });
    }

    private Inventory buildMainMenu(boolean admin, ViewMode mode, int page, List<WorldEntry> worlds, boolean loading) {
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
                inv.setItem(i, worldIcon(entry, mode));
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

    private void openSettingsMenu(Player player, String worldName, int returnPage) {
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<WorldEntry> entryOpt = repository.findByWorldName(worldName);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player onlinePlayer = Bukkit.getPlayer(playerId);
                if (onlinePlayer == null || !onlinePlayer.isOnline()) {
                    return;
                }

                if (entryOpt.isEmpty()) {
                    send(onlinePlayer, "world-not-found");
                    openMainMenu(onlinePlayer, ViewMode.OWN, 0);
                    return;
                }

                WorldEntry entry = entryOpt.get();
                Inventory inv = buildSettingsMenu(worldName, returnPage, entry);
                onlinePlayer.openInventory(inv);
            });
        });
    }

    private Inventory buildSettingsMenu(String worldName, int returnPage, WorldEntry entry) {
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

        return inv;
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
            return repository.listInvitedWorlds(playerName);
        }

        if (mode == ViewMode.ARCHIVED) {
            return admin ? repository.listArchivedWorlds() : List.of();
        }

        Map<String, WorldEntry> ticketWorlds = new ConcurrentHashMap<>();
        for (WorldEntry entry : repository.listOwnWorlds(ownerUuid)) {
            if (isTicketWorld(entry)) {
                ticketWorlds.put(entry.worldName().toLowerCase(Locale.ROOT), entry);
            }
        }

        List<WorldEntry> discoverable = repository.listDiscoverableWorlds(ownerUuid, admin);
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
        int nextIndex = repository.nextWorldIndex(player.getUniqueId().toString());
        String worldName = player.getName() + "-" + nextIndex;
        createWorld(player, worldName, null, false, "private", null, List.of());
    }

    private void createWorld(Player player, String worldName, String orderLabel) {
        createWorld(player, worldName, orderLabel, false, orderLabel == null ? "private" : "ticket", orderLabel, List.of());
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
        int nextIndex = repository.nextWorldIndex(player.getUniqueId().toString());

        WorldCreator creator = new WorldCreator(worldName)
            .environment(World.Environment.NORMAL)
            .type(WorldType.FLAT)
            .generateStructures(false)
            .generatorSettings("{\"biome\":\"minecraft:plains\",\"features\":false,\"lakes\":false,\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:dirt\",\"height\":100},{\"block\":\"minecraft:grass_block\",\"height\":1}]}");

        World created = Bukkit.createWorld(creator);
        if (created == null) {
            send(player, "create-failed", "%world%", worldName);
            return;
        }

        ConsoleCommandSender console = Bukkit.getConsoleSender();
        Bukkit.dispatchCommand(console, "mv import " + worldName + " normal");

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
                    false
                );

                send(player, "create-success", "%world%", worldName);
                if (orderLabel != null) {
                    player.sendMessage("§aVerknüpfter Auftrag: §f#" + orderLabel);
                }
                openMainMenu(player, ViewMode.OWN, 0);
            }
        }.runTaskLater(plugin, 20L);
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
        boolean wasRetry
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
                false
            );

            if (persisted) {
                if (wasRetry) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player online = Bukkit.getPlayer(playerId);
                        if (online != null && online.isOnline()) {
                            online.sendMessage("§aWelt wurde jetzt mit dem Dashboard synchronisiert.");
                        }
                    });
                }
                return;
            }

            if (retriesLeft <= 0) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player online = Bukkit.getPlayer(playerId);
                    if (online != null && online.isOnline()) {
                        online.sendMessage("§eWelt wurde erstellt, aber API war nicht erreichbar. Sie erscheint im Dashboard, sobald die API wieder erreichbar ist.");
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
                    true
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

        repository.archiveWorld(worldName);
        send(player, "delete-success", "%world%", worldName);
    }

    private void evacuatePlayersFromWorld(String worldName) {
        World target = Bukkit.getWorld(worldName);
        if (target == null) {
            return;
        }

        List<Player> affectedPlayers = new ArrayList<>(target.getPlayers());
        for (Player affected : affectedPlayers) {
            World ownFallback = resolveOwnFallbackWorld(affected, worldName);
            if (ownFallback == null) {
                affected.kickPlayer("Diese Welt wurde gelöscht.");
                continue;
            }

            Location destination = ownFallback.getSpawnLocation();
            affected.teleport(destination);
            affected.sendMessage("§eDiese Welt wurde gelöscht. Du wurdest in deine Welt §f" + ownFallback.getName() + " §eteleportiert.");
        }
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
            player.sendMessage("§cUngültige Auftragsnummer. Format: A010");
            return;
        }

        Optional<WorldEntry> entryOpt = repository
            .listOwnWorlds(player.getUniqueId().toString())
            .stream()
            .filter(entry -> normalizedOrder.equalsIgnoreCase(entry.ticketOrderId()) || normalizedOrder.equalsIgnoreCase(entry.orderLabel()))
            .findFirst();

        if (entryOpt.isEmpty()) {
            player.sendMessage("§cKeine Welt für diesen Auftrag gefunden: §f" + normalizedOrder);
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
        if (notify) {
            send(player, "join-success", "%world%", worldName);
        }
        return true;
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
        if (entry.ticketOrderId() != null && !entry.ticketOrderId().isBlank()) {
            lore.add(Component.text("Ticket: #" + entry.ticketOrderId(), NamedTextColor.GOLD));
        }
        if (entry.orderLabel() != null && !entry.orderLabel().isBlank()) {
            lore.add(Component.text("Auftrag: #" + entry.orderLabel(), NamedTextColor.AQUA));
        }
        if (entry.customers() != null && !entry.customers().isEmpty()) {
            lore.add(Component.text("Kunden: " + String.join(", ", entry.customers()), NamedTextColor.LIGHT_PURPLE));
        }
        lore.add(Component.text("Einladungen: " + entry.invitedPlayers().size() + " | Trust: " + entry.trustedPlayers().size(), NamedTextColor.GRAY));
        lore.add(Component.text(entry.isPublic() ? "Status: Öffentlich" : "Status: Privat", entry.isPublic() ? NamedTextColor.GREEN : NamedTextColor.RED));
        if (entry.isArchived()) {
            lore.add(Component.text("Status: Archiviert", NamedTextColor.RED));
        }
        if (mode == ViewMode.INVITED) {
            lore.add(Component.text("Du bist in dieser Welt eingeladen.", NamedTextColor.AQUA));
        }
        lore.add(Component.empty());
        lore.add(Component.text("Linksklick: Beitreten", NamedTextColor.YELLOW));
        if (mode == ViewMode.OWN) {
            lore.add(Component.text("Rechtsklick: Einstellungen", NamedTextColor.YELLOW));
        }

        return namedWorldItem(material, "§b" + entry.displayName(), entry.worldName(), lore);
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

    private record PendingDeleteConfirmation(String worldName, int code, long expiresAtEpochMs) {
    }

    private record PendingTicketWorld(String worldName, List<String> customers, boolean archived) {
    }
}
