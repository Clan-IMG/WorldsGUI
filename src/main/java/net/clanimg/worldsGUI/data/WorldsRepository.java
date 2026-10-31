package net.clanimg.worldsGUI.data;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.clanimg.worldsGUI.model.WorldEntry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

public final class WorldsRepository {
    private static final long WARNING_COOLDOWN_MS = 30_000L;
    /** Upper bound for a read on the server thread (config {@code api.main-thread-timeout-ms}). */
    private static final long DEFAULT_MAIN_THREAD_READ_TIMEOUT_MS = 1_500L;
    private static final int CIRCUIT_FAILURE_THRESHOLD = 2;
    private static final long CIRCUIT_OPEN_MS = 15_000L;

    private final JavaPlugin plugin;
    private String apiBaseUrl;
    private String apiToken;
    private volatile HttpClient httpClient;
    private Duration connectTimeout;
    private Duration requestTimeout;
    private final Gson gson;
    private final Map<String, Long> warningCooldowns = new ConcurrentHashMap<>();
    private final Map<String, ApiCircuit> circuits = new ConcurrentHashMap<>();
    private String lastInitializeError = "Unbekannter Fehler";

    public record OrderAssignmentCheck(boolean exists, boolean assigned) {}
    /** failed = API nicht erreichbar/Fehler; dann ist unbekannt, ob die Welt existiert. */
    public record WorldLookup(boolean failed, Optional<WorldEntry> entry) {}
    public record OrderSummary(String orderId, String customerName) {}

    public WorldsRepository(
        JavaPlugin plugin,
        String apiBaseUrl,
        String apiToken,
        Duration connectTimeout,
        Duration requestTimeout
    ) {
        this.plugin = plugin;
        this.apiBaseUrl = trimTrailingSlash(apiBaseUrl);
        this.apiToken = apiToken;
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.httpClient = buildHttpClient(connectTimeout);
        this.gson = new Gson();
    }

    public synchronized void reloadApiSettings(
        String apiBaseUrl,
        String apiToken,
        Duration connectTimeout,
        Duration requestTimeout
    ) {
        this.apiBaseUrl = trimTrailingSlash(apiBaseUrl);
        this.apiToken = apiToken;
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.httpClient = buildHttpClient(connectTimeout);
    }

    /**
     * HTTP/1.1 statt des HTTP/2-Defaults: Bei HTTP/2 laufen alle Requests über eine einzige Verbindung.
     * Wird die stillschweigend getrennt (NAT/Proxy), hängt jeder Request bis zum Timeout, bis der Kernel
     * die Verbindung nach Minuten verwirft. Bei HTTP/1.1 wird die Verbindung beim Timeout geschlossen.
     */
    private static HttpClient buildHttpClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connectTimeout)
            .build();
    }

    /** Verwirft alle gepoolten Verbindungen, damit nach einem Timeout keine toten Verbindungen wiederverwendet werden. */
    private synchronized void resetHttpClient(HttpClient failedClient) {
        if (httpClient == failedClient) {
            httpClient = buildHttpClient(connectTimeout);
            failedClient.shutdown();
            plugin.getLogger().warning("API-Request timed out - HTTP-Verbindungen werden neu aufgebaut.");
        }
    }

    public boolean initialize() {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            lastInitializeError = "API base URL fehlt";
            return false;
        }
        if (apiToken == null || apiToken.isBlank()) {
            lastInitializeError = "API token fehlt";
            return false;
        }

        try {
            ApiResponse response = request("POST", "/worlds/bootstrap", "{}");
            if (response.statusCode() / 100 != 2) {
                lastInitializeError = "Bootstrap fehlgeschlagen (HTTP " + response.statusCode() + ")";
                return false;
            }
            lastInitializeError = "";
            return true;
        } catch (Exception ex) {
            lastInitializeError = ex.getMessage();
            plugin.getLogger().severe("Fehler beim Initialisieren über API: " + ex.getMessage());
            return false;
        }
    }

    public String lastInitializeError() {
        return lastInitializeError == null || lastInitializeError.isBlank() ? "Unbekannter Fehler" : lastInitializeError;
    }

    public int nextWorldIndex(String ownerUuid) {
        try {
            String path = "/worlds/next-index?ownerUuid=" + encode(ownerUuid);
            ApiResponse response = request("GET", path, null);
            if (response.statusCode() / 100 != 2) {
                warnThrottled("nextWorldIndex", "nextWorldIndex API Fehler: HTTP " + response.statusCode());
                return 1;
            }
            JsonObject json = parseObject(response.body());
            return getInt(json, "nextIndex", 1);
        } catch (Exception ex) {
            warnThrottled("nextWorldIndex-ex", "Fehler beim Ermitteln des Weltindex via API: " + ex.getMessage());
            return 1;
        }
    }

    public boolean insertWorld(
        String worldName,
        String ownerUuid,
        String ownerName,
        String orderLabel,
        String ticketOrderId,
        String sourceType,
        Integer builderUserId,
        List<String> customers,
        int worldIndex,
        String displayName,
        String iconMaterial,
        boolean isPublic,
        boolean isArchived
    ) {
        return insertWorld(
            worldName,
            ownerUuid,
            ownerName,
            orderLabel,
            ticketOrderId,
            sourceType,
            builderUserId,
            customers,
            worldIndex,
            displayName,
            iconMaterial,
            isPublic,
            isArchived,
            null
        );
    }

    public boolean insertWorld(
        String worldName,
        String ownerUuid,
        String ownerName,
        String orderLabel,
        String ticketOrderId,
        String sourceType,
        Integer builderUserId,
        List<String> customers,
        int worldIndex,
        String displayName,
        String iconMaterial,
        boolean isPublic,
        boolean isArchived,
        String serverNameOverride
    ) {
        JsonObject body = new JsonObject();
        body.addProperty("worldName", worldName);
        body.addProperty("ownerUuid", ownerUuid);
        body.addProperty("ownerName", ownerName);
        if (orderLabel == null) {
            body.add("orderLabel", null);
        } else {
            body.addProperty("orderLabel", orderLabel);
        }
        if (ticketOrderId == null) {
            body.add("ticketOrderId", null);
        } else {
            body.addProperty("ticketOrderId", ticketOrderId);
        }
        if (sourceType == null) {
            body.add("sourceType", null);
        } else {
            body.addProperty("sourceType", sourceType);
        }
        if (builderUserId == null) {
            body.add("builderUserId", null);
        } else {
            body.addProperty("builderUserId", builderUserId);
        }
        JsonArray customerValues = new JsonArray();
        for (String customer : customers == null ? List.<String>of() : customers) {
            if (customer != null && !customer.isBlank()) {
                customerValues.add(customer.trim());
            }
        }
        body.add("customers", customerValues);
        body.addProperty("worldIndex", worldIndex);
        body.addProperty("displayName", displayName);
        body.addProperty("iconMaterial", iconMaterial);
        String targetServerName = serverNameOverride == null ? "" : serverNameOverride.trim();
        if (targetServerName.isBlank()) {
            String localServerName = resolveLocalServerId();
            if (!localServerName.isBlank()) {
                targetServerName = localServerName;
            }
        }
        if (!targetServerName.isBlank()) {
            body.addProperty("serverName", targetServerName);
        }
        body.addProperty("isPublic", isPublic);
        body.addProperty("isArchived", isArchived);
        try {
            ApiResponse response = request("POST", "/worlds", gson.toJson(body));
            int status = response.statusCode();
            if (status / 100 != 2) {
                if (status == 409) {
                    // Duplicate/exists can happen on retries and should be treated as idempotent success.
                    return true;
                }
                warnThrottled(
                    "post-worlds-status",
                    "POST /worlds fehlgeschlagen: HTTP " + status + " body=" + abbreviate(response.body(), 280)
                );
                return false;
            }
            return true;
        } catch (Exception ex) {
            warnThrottled("post-worlds-ex", "POST /worlds fehlgeschlagen: " + ex.getMessage());
            return false;
        }
    }

    public OrderAssignmentCheck checkOrderAssignment(String orderId, String minecraftName) {
        try {
            ApiResponse response = request(
                resolveOrdersApiBaseUrl(),
                resolveOrdersApiToken(),
                "GET",
                "/orders/" + encode(orderId) + "/assignment-check?minecraftName=" + encode(minecraftName),
                null
            );
            if (response.statusCode() / 100 != 2) {
                plugin.getLogger().warning("order-exists API Fehler: HTTP " + response.statusCode());
                return new OrderAssignmentCheck(false, false);
            }
            JsonObject json = parseObject(response.body());
            return new OrderAssignmentCheck(getBoolean(json, "exists", false), getBoolean(json, "assigned", false));
        } catch (Exception ex) {
            plugin.getLogger().warning("Fehler beim Prüfen der Auftragsexistenz via API: " + ex.getMessage());
            return new OrderAssignmentCheck(false, false);
        }
    }

    public Optional<OrderSummary> getOrderSummary(String orderId) {
        String normalized = orderId == null ? "" : orderId.trim();
        if (normalized.isBlank()) {
            return Optional.empty();
        }

        try {
            ApiResponse response = request(
                resolveOrdersApiBaseUrl(),
                resolveOrdersApiToken(),
                "GET",
                "/orders/" + encode(normalized),
                null
            );
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() / 100 != 2) {
                plugin.getLogger().warning("order summary API Fehler: HTTP " + response.statusCode());
                return Optional.empty();
            }

            JsonObject json = parseObject(response.body());
            JsonObject order = json.has("order") && json.get("order").isJsonObject()
                ? json.getAsJsonObject("order")
                : json;
            String customerName = getString(order, "mcName", "");
            return Optional.of(new OrderSummary(normalized, customerName));
        } catch (Exception ex) {
            plugin.getLogger().warning("Fehler beim Laden der Auftragsdaten via API: " + ex.getMessage());
            return Optional.empty();
        }
    }

    public List<WorldEntry> listTicketWorlds(String orderId) {
        return listWorlds("/worlds/ticket/" + encode(orderId), "ticket worlds");
    }

    public List<WorldEntry> listOpenTicketWorlds() {
        return listWorlds("/worlds/tickets/open", "open ticket worlds");
    }

    public List<WorldEntry> listServerWorlds(String serverName) {
        String normalized = serverName == null ? "" : serverName.trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        return listWorlds("/worlds/server/" + encode(normalized), "server worlds");
    }

    public List<WorldEntry> listArchivedWorlds() {
        return listWorlds("/worlds/archived", "archived worlds");
    }

    public List<String> listAssignedOpenOrderIds(String minecraftName) {
        List<String> orderIds = new ArrayList<>();
        String needle = minecraftName == null ? "" : minecraftName.trim();
        if (needle.isBlank()) {
            return orderIds;
        }

        try {
            ApiResponse response = request(
                resolveOrdersApiBaseUrl(),
                resolveOrdersApiToken(),
                "GET",
                "/orders/assigned-open?minecraftName=" + encode(needle),
                null
            );
            if (response.statusCode() / 100 != 2) {
                plugin.getLogger().warning("assigned-open API Fehler: HTTP " + response.statusCode());
                return orderIds;
            }

            JsonObject json = parseObject(response.body());
            JsonArray rows = json.has("orderIds") && json.get("orderIds").isJsonArray()
                ? json.getAsJsonArray("orderIds")
                : new JsonArray();
            for (JsonElement element : rows) {
                if (!element.isJsonPrimitive()) {
                    continue;
                }
                String value = element.getAsString();
                if (value != null && !value.isBlank()) {
                    orderIds.add(value.trim());
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Fehler beim Laden zugewiesener Aufträge via API: " + ex.getMessage());
        }

        return orderIds;
    }

    public boolean setInvitedPlayers(String worldName, List<String> players) {
        JsonObject body = new JsonObject();
        JsonArray values = new JsonArray();
        for (String player : players) {
            values.add(player);
        }
        body.add("values", values);
        return patchOrWarn("/worlds/" + encode(worldName) + "/invited-players", body);
    }

    public boolean setTrustedPlayers(String worldName, List<String> players) {
        JsonObject body = new JsonObject();
        JsonArray values = new JsonArray();
        for (String player : players) {
            values.add(player);
        }
        body.add("values", values);
        return patchOrWarn("/worlds/" + encode(worldName) + "/trusted-players", body);
    }

    public Optional<WorldEntry> findByWorldName(String worldName) {
        return lookupWorld(worldName).entry();
    }

    /**
     * Wie findByWorldName, unterscheidet aber "existiert nicht" (404) von einem API-Fehler -
     * wichtig überall dort, wo "nicht gefunden" dazu führt, dass eine Welt neu angelegt wird.
     */
    public WorldLookup lookupWorld(String worldName) {
        try {
            ApiResponse response = request("GET", "/worlds/" + encode(worldName), null);
            if (response.statusCode() == 404) {
                return new WorldLookup(false, Optional.empty());
            }
            if (response.statusCode() / 100 != 2) {
                warnThrottled("findByWorldName", "findByWorldName API Fehler: HTTP " + response.statusCode());
                return new WorldLookup(true, Optional.empty());
            }
            JsonObject json = parseObject(response.body());
            JsonObject world = json.has("world") && json.get("world").isJsonObject()
                ? json.getAsJsonObject("world")
                : null;
            if (world == null) {
                return new WorldLookup(false, Optional.empty());
            }
            return new WorldLookup(false, Optional.of(mapEntry(world)));
        } catch (Exception ex) {
            warnThrottled("findByWorldName-ex", "Fehler beim Laden der Weltdaten via API: " + ex.getMessage());
            return new WorldLookup(true, Optional.empty());
        }
    }

    public List<WorldEntry> listOwnWorlds(String ownerUuid) {
        return listWorlds("/worlds/owner/" + encode(ownerUuid), "eigener Welten");
    }

    /**
     * Like {@link #listOwnWorlds}, but tells "the API could not be asked" (empty Optional) apart from "the player has
     * no worlds" (empty list) – a decision like creating a personal world must not be made on a failed request.
     */
    public Optional<List<WorldEntry>> tryListOwnWorlds(String ownerUuid) {
        return fetchWorlds("/worlds/owner/" + encode(ownerUuid), "eigener Welten");
    }

    public List<WorldEntry> listDiscoverableWorlds(String viewerUuid, String viewerMinecraftName, boolean admin) {
        String path = "/worlds/discoverable?viewerUuid=" + encode(viewerUuid)
            + "&viewerMinecraftName=" + encode(viewerMinecraftName == null ? "" : viewerMinecraftName)
            + "&admin=" + admin;
        return listWorlds(path, "öffentlicher Welten");
    }

    public List<WorldEntry> listInvitedWorlds(String playerName) {
        return listWorlds("/worlds/invited/" + encode(playerName), "eingeladener Welten");
    }

    public void setPublic(String worldName, boolean value) {
        JsonObject body = new JsonObject();
        body.addProperty("value", value);
        patchOrWarn("/worlds/" + encode(worldName) + "/public", body);
    }

    public boolean setDisplayName(String worldName, String value) {
        JsonObject body = new JsonObject();
        body.addProperty("value", value);
        body.addProperty("displayName", value);
        return patchOrWarn("/worlds/" + encode(worldName) + "/display-name", body);
    }

    public void setIcon(String worldName, String value) {
        JsonObject body = new JsonObject();
        body.addProperty("value", value);
        patchOrWarn("/worlds/" + encode(worldName) + "/icon", body);
    }

    public boolean setSpawn(String worldName, Location loc) {
        JsonObject body = new JsonObject();
        body.addProperty("x", loc.getX());
        body.addProperty("y", loc.getY());
        body.addProperty("z", loc.getZ());
        body.addProperty("yaw", loc.getYaw());
        body.addProperty("pitch", loc.getPitch());
        return patchOrWarn("/worlds/" + encode(worldName) + "/spawn", body);
    }

    public void deleteWorld(String worldName) {
        try {
            ApiResponse response = request("DELETE", "/worlds/" + encode(worldName), null);
            if (response.statusCode() / 100 != 2) {
                plugin.getLogger().warning("deleteWorld API Fehler: HTTP " + response.statusCode());
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Fehler beim Löschen der Welt via API: " + ex.getMessage());
        }
    }

    public void archiveWorld(String worldName) {
        try {
            ApiResponse response = request("POST", "/worlds/" + encode(worldName) + "/archive", "{}");
            if (response.statusCode() / 100 != 2) {
                plugin.getLogger().warning("archiveWorld API Fehler: HTTP " + response.statusCode());
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Fehler beim Archivieren der Welt via API: " + ex.getMessage());
        }
    }

    public void unarchiveWorld(String worldName) {
        try {
            ApiResponse response = request("POST", "/worlds/" + encode(worldName) + "/unarchive", "{}");
            if (response.statusCode() / 100 != 2) {
                plugin.getLogger().warning("unarchiveWorld API Fehler: HTTP " + response.statusCode());
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Fehler beim Entarchivieren der Welt via API: " + ex.getMessage());
        }
    }

    public void upsertPlayerPresence(String playerName, String currentWorld, boolean online) {
        JsonObject body = new JsonObject();
        body.addProperty("playerName", playerName);
        if (currentWorld == null) {
            body.add("currentWorld", null);
        } else {
            body.addProperty("currentWorld", currentWorld);
        }
        body.addProperty("online", online);

        try {
            ApiResponse response = request("PUT", "/worlds/presence", gson.toJson(body));
            if (response.statusCode() / 100 != 2) {
                warnThrottled("presence", "Presence API Fehler: HTTP " + response.statusCode());
            }
        } catch (Exception ex) {
            warnThrottled("presence-ex", "Fehler beim Aktualisieren der Presence via API: " + ex.getMessage());
        }
    }

    public void upsertPendingWorldTransfer(String playerName, String worldName) {
        JsonObject body = new JsonObject();
        body.addProperty("playerName", playerName);
        if (worldName == null || worldName.isBlank()) {
            body.add("worldName", null);
        } else {
            body.addProperty("worldName", worldName);
        }

        try {
            ApiResponse response = request("PUT", "/worlds/presence/pending-world", gson.toJson(body));
            if (response.statusCode() / 100 != 2) {
                warnThrottled("pending-world", "Pending-World API Fehler: HTTP " + response.statusCode());
            }
        } catch (Exception ex) {
            warnThrottled("pending-world-ex", "Fehler beim Aktualisieren der Pending-World via API: " + ex.getMessage());
        }
    }

    public String consumePendingWorldTransfer(String playerName) {
        JsonObject body = new JsonObject();
        body.addProperty("playerName", playerName);

        try {
            ApiResponse response = request("POST", "/worlds/presence/pending-world/consume", gson.toJson(body));
            if (response.statusCode() / 100 != 2) {
                warnThrottled("pending-world-consume", "Pending-World-Consume API Fehler: HTTP " + response.statusCode());
                return "";
            }

            JsonObject json = parseObject(response.body());
            String pendingWorld = getString(json, "pendingWorld", "");
            return pendingWorld == null ? "" : pendingWorld.trim();
        } catch (Exception ex) {
            warnThrottled("pending-world-consume-ex", "Fehler beim Laden der Pending-World via API: " + ex.getMessage());
            return "";
        }
    }

    /** failed = API nicht erreichbar/Fehler; worldSort null = noch keine Sortierung gewählt. */
    public record PlayerSettingsLookup(boolean failed, String worldSort) {}

    /**
     * Meldet, dass der Spieler eine Welt betreten hat. Die API speichert nur Welten, die es gibt.
     * @param visitedAt Epoch-Millis, nur für die Übernahme alter Daten; {@code null} = jetzt (API-Zeit)
     */
    public boolean recordWorldVisit(String playerUuid, String worldName, Long visitedAt) {
        JsonObject body = new JsonObject();
        body.addProperty("worldName", worldName);
        if (visitedAt != null) {
            body.addProperty("visitedAt", visitedAt);
        }
        try {
            ApiResponse response = request("PUT", "/players/" + encode(playerUuid) + "/world-visits", gson.toJson(body));
            if (response.statusCode() / 100 != 2) {
                warnThrottled("world-visit", "Welt-Besuch API Fehler: HTTP " + response.statusCode());
                return false;
            }
            return true;
        } catch (Exception ex) {
            warnThrottled("world-visit-ex", "Fehler beim Speichern des Welt-Besuchs via API: " + ex.getMessage());
            return false;
        }
    }

    /** Letzter Besuch je Welt (Epoch-Millis); leer, wenn die API nicht gefragt werden konnte. */
    public Optional<Map<String, Long>> fetchWorldVisits(String playerUuid) {
        try {
            ApiResponse response = request("GET", "/players/" + encode(playerUuid) + "/world-visits", null);
            if (response.statusCode() / 100 != 2) {
                warnThrottled("world-visits", "Welt-Besuche API Fehler: HTTP " + response.statusCode());
                return Optional.empty();
            }
            JsonObject json = parseObject(response.body());
            Map<String, Long> visits = new HashMap<>();
            if (json.has("visits") && json.get("visits").isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray("visits")) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject visit = element.getAsJsonObject();
                    String worldName = getString(visit, "worldName", "");
                    if (!worldName.isBlank() && visit.has("visitedAt") && !visit.get("visitedAt").isJsonNull()) {
                        visits.put(worldName, visit.get("visitedAt").getAsLong());
                    }
                }
            }
            return Optional.of(visits);
        } catch (Exception ex) {
            warnThrottled("world-visits-ex", "Fehler beim Laden der Welt-Besuche via API: " + ex.getMessage());
            return Optional.empty();
        }
    }

    public PlayerSettingsLookup fetchPlayerSettings(String playerUuid) {
        try {
            ApiResponse response = request("GET", "/players/" + encode(playerUuid) + "/settings", null);
            if (response.statusCode() / 100 != 2) {
                warnThrottled("player-settings", "Spieler-Einstellungen API Fehler: HTTP " + response.statusCode());
                return new PlayerSettingsLookup(true, null);
            }
            JsonObject json = parseObject(response.body());
            JsonObject settings = json.has("settings") && json.get("settings").isJsonObject()
                ? json.getAsJsonObject("settings")
                : new JsonObject();
            return new PlayerSettingsLookup(false, getNullableString(settings, "worldSort"));
        } catch (Exception ex) {
            warnThrottled("player-settings-ex", "Fehler beim Laden der Spieler-Einstellungen via API: " + ex.getMessage());
            return new PlayerSettingsLookup(true, null);
        }
    }

    public boolean updateWorldSort(String playerUuid, String worldSort) {
        JsonObject body = new JsonObject();
        body.addProperty("worldSort", worldSort);
        try {
            ApiResponse response = request("PUT", "/players/" + encode(playerUuid) + "/settings", gson.toJson(body));
            if (response.statusCode() / 100 != 2) {
                warnThrottled("player-settings-put", "Spieler-Einstellungen API Fehler: HTTP " + response.statusCode());
                return false;
            }
            return true;
        } catch (Exception ex) {
            warnThrottled("player-settings-put-ex", "Fehler beim Speichern der Spieler-Einstellungen via API: " + ex.getMessage());
            return false;
        }
    }

    public void upsertServerPresence(String serverName, boolean online, String status, int heartbeatIntervalSeconds) {
        upsertServerPresence(serverName, online, status, heartbeatIntervalSeconds, null);
    }

    /** @param serverGroup Template-Gruppe, wenn dieser Server eine Instanz davon ist (lobby für lobby-1), sonst null */
    public void upsertServerPresence(String serverName, boolean online, String status, int heartbeatIntervalSeconds, String serverGroup) {
        String normalizedServer = serverName == null ? "" : serverName.trim();
        if (normalizedServer.isBlank()) {
            return;
        }

        JsonObject body = new JsonObject();
        body.addProperty("serverName", normalizedServer);
        body.addProperty("online", online);
        body.addProperty("status", status == null ? "" : status);
        body.addProperty("heartbeatIntervalSeconds", Math.max(1, heartbeatIntervalSeconds));
        if (serverGroup != null && !serverGroup.isBlank()) {
            body.addProperty("serverGroup", serverGroup.trim());
        }

        try {
            ApiResponse response = request("PUT", "/worlds/server-presence", gson.toJson(body));
            if (response.statusCode() / 100 != 2) {
                warnThrottled("server-presence", "Server-Presence API Fehler: HTTP " + response.statusCode());
            }
        } catch (Exception ex) {
            warnThrottled("server-presence-ex", "Fehler beim Aktualisieren der Server-Presence via API: " + ex.getMessage());
        }
    }

    public List<String> listOnlineServerNames(int graceSeconds) {
        List<String> serverNames = new ArrayList<>();
        try {
            String path = "/worlds/servers/online?graceSeconds=" + Math.max(1, graceSeconds);
            ApiResponse response = request("GET", path, null);
            if (response.statusCode() / 100 != 2) {
                warnThrottled("online-servers", "Online-Server API Fehler: HTTP " + response.statusCode());
                return serverNames;
            }

            JsonObject json = parseObject(response.body());
            JsonArray rows = json.has("servers") && json.get("servers").isJsonArray()
                ? json.getAsJsonArray("servers")
                : new JsonArray();
            for (JsonElement element : rows) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject row = element.getAsJsonObject();
                String name = getString(row, "serverName", "");
                if (name != null && !name.isBlank()) {
                    serverNames.add(name.trim());
                }
            }
        } catch (Exception ex) {
            warnThrottled("online-servers-ex", "Fehler beim Laden online Server via API: " + ex.getMessage());
        }
        return serverNames;
    }

    /**
     * Server, auf dem WorldsGUI läuft bzw. gelaufen ist; online = gerade erreichbar (Heartbeat).
     * group = Template-Gruppe bei Instanzen (lobby für lobby-1), sonst null.
     */
    public record KnownServer(String name, boolean online, String group) {
        public KnownServer(String name, boolean online) {
            this(name, online, null);
        }

        /** Eintrag steht für ein ganzes Template (alle Instanzen), nicht für einen einzelnen Server. */
        public boolean isTemplate() {
            return group != null && group.equalsIgnoreCase(name);
        }
    }

    /**
     * Alle Server, auf denen WorldsGUI in den letzten {@code maxAgeDays} Tagen lief - auch gerade ausgeschaltete.
     * Leer, wenn die API nicht gefragt werden konnte (oder noch keinen /worlds/servers/known-Endpunkt hat).
     */
    public Optional<List<KnownServer>> listKnownServers(int maxAgeDays, int graceSeconds) {
        try {
            String path = "/worlds/servers/known?maxAgeDays=" + Math.max(1, maxAgeDays) + "&graceSeconds=" + Math.max(1, graceSeconds);
            ApiResponse response = request("GET", path, null);
            if (response.statusCode() / 100 != 2) {
                warnThrottled("known-servers", "Server-Liste API Fehler: HTTP " + response.statusCode());
                return Optional.empty();
            }
            JsonObject json = parseObject(response.body());
            List<KnownServer> servers = new ArrayList<>();
            if (json.has("servers") && json.get("servers").isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray("servers")) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject row = element.getAsJsonObject();
                    String name = getString(row, "serverName", "");
                    if (name != null && !name.isBlank()) {
                        String group = getNullableString(row, "serverGroup");
                        servers.add(new KnownServer(name.trim(), getBoolean(row, "online", false),
                            group == null || group.isBlank() ? null : group.trim()));
                    }
                }
            }
            return Optional.of(servers);
        } catch (Exception ex) {
            warnThrottled("known-servers-ex", "Fehler beim Laden der Server-Liste via API: " + ex.getMessage());
            return Optional.empty();
        }
    }

    public List<JoinRequest> listPendingJoinRequests(int limit) {
        List<JoinRequest> requests = new ArrayList<>();
        try {
            String path = "/worlds/join-requests/pending?limit=" + Math.max(1, limit);
            ApiResponse response = request("GET", path, null);
            if (response.statusCode() / 100 != 2) {
                warnThrottled("join-requests", "JoinRequest API Fehler: HTTP " + response.statusCode());
                return requests;
            }

            JsonObject json = parseObject(response.body());
            JsonArray rows = json.has("requests") && json.get("requests").isJsonArray()
                ? json.getAsJsonArray("requests")
                : new JsonArray();
            for (JsonElement element : rows) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject row = element.getAsJsonObject();
                requests.add(new JoinRequest(
                    getLong(row, "id", 0L),
                    getString(row, "playerName", ""),
                    getString(row, "worldName", "")
                ));
            }
        } catch (Exception ex) {
            warnThrottled("join-requests-ex", "Fehler beim Laden der Join-Requests via API: " + ex.getMessage());
        }
        return requests;
    }

    public void markJoinRequest(long id, String status, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("status", status);
        if (message == null) {
            body.add("message", null);
        } else {
            body.addProperty("message", message);
        }
        patchOrWarn("/worlds/join-requests/" + id, body);
    }

    // ------------------------------------------------------------------
    // Welt-Backups (API-Endpunkte unter /world-backups)
    // ------------------------------------------------------------------

    public Optional<WorldBackupOverview> loadBackupOverview(String worldName) {
        try {
            ApiResponse response = request("GET", "/world-backups/" + encode(worldName), null);
            if (response.statusCode() / 100 != 2) {
                warnThrottled("backup-overview", "Backup-Übersicht API Fehler: HTTP " + response.statusCode());
                return Optional.empty();
            }

            JsonObject json = parseObject(response.body());
            List<BackupEntry> backups = new ArrayList<>();
            for (JsonObject row : objectRows(json, "backups")) {
                backups.add(new BackupEntry(
                    getLong(row, "id", 0L),
                    getString(row, "kind", "auto"),
                    getLong(row, "sizeBytes", 0L),
                    getString(row, "createdAt", ""),
                    getString(row, "sha256", "")
                ));
            }
            List<BackupJob> jobs = new ArrayList<>();
            for (JsonObject row : objectRows(json, "jobs")) {
                jobs.add(mapBackupJob(row));
            }
            return Optional.of(new WorldBackupOverview(getInt(json, "manualLimit", 7), backups, jobs));
        } catch (Exception ex) {
            warnThrottled("backup-overview-ex", "Fehler beim Laden der Backups via API: " + ex.getMessage());
            return Optional.empty();
        }
    }

    public BackupApiResult createBackupJob(String worldName, String jobType, Long backupId, String requestedBy) {
        JsonObject body = new JsonObject();
        body.addProperty("worldName", worldName);
        body.addProperty("jobType", jobType);
        if (backupId != null) {
            body.addProperty("backupId", backupId);
        }
        if (requestedBy != null) {
            body.addProperty("requestedBy", requestedBy);
        }
        return backupCall("POST", "/world-backups/jobs", body);
    }

    /**
     * Duplikat einer Welt über die Backup-Kette: der Server der Quellwelt erstellt ein Backup, danach spielt die API es
     * automatisch auf dem Server der (bereits angelegten) Zielwelt ein.
     */
    public BackupApiResult createCloneJob(String sourceWorldName, String targetWorldName, String requestedBy) {
        JsonObject body = new JsonObject();
        body.addProperty("worldName", sourceWorldName);
        body.addProperty("jobType", "clone");
        body.addProperty("cloneTarget", targetWorldName);
        if (requestedBy != null) {
            body.addProperty("requestedBy", requestedBy);
        }
        return backupCall("POST", "/world-backups/jobs", body);
    }

    public BackupApiResult cancelBackupJob(long jobId, String worldName) {
        return backupCall("POST", "/world-backups/jobs/" + jobId + "/cancel?worldName=" + encode(worldName), new JsonObject());
    }

    public BackupApiResult deleteBackup(long backupId, String worldName) {
        return backupCall("DELETE", "/world-backups/items/" + backupId + "?worldName=" + encode(worldName), null);
    }

    /** @param serverGroup Template-Gruppe dieser Instanz (holt auch Aufträge für Welten der Gruppe ab) oder null */
    public List<BackupJob> claimBackupJobs(String serverName, String serverGroup, int limit) {
        JsonObject body = new JsonObject();
        body.addProperty("serverName", serverName);
        if (serverGroup != null && !serverGroup.isBlank()) {
            body.addProperty("serverGroup", serverGroup.trim());
        }
        body.addProperty("limit", Math.max(1, limit));
        return backupJobList(backupCall("POST", "/world-backups/jobs/claim", body), "claim");
    }

    public List<BackupJob> listUnnotifiedBackupJobs(int limit) {
        return backupJobList(backupCall("GET", "/world-backups/jobs/unnotified?limit=" + Math.max(1, limit), null), "unnotified");
    }

    public void markBackupJobNotified(long jobId) {
        backupCall("POST", "/world-backups/jobs/" + jobId + "/notified", new JsonObject());
    }

    public JobHeartbeat heartbeatBackupJob(long jobId, String serverName, String phase) {
        JsonObject body = new JsonObject();
        body.addProperty("serverName", serverName);
        if (phase != null) {
            body.addProperty("phase", phase);
        }
        BackupApiResult result = backupCall("POST", "/world-backups/jobs/" + jobId + "/heartbeat", body);
        if (!result.ok()) {
            // API nicht erreichbar oder Fehler: weiterarbeiten, der Server-Heartbeat holt sich später wieder ein.
            return new JobHeartbeat(false, true);
        }
        return new JobHeartbeat(true, result.json().has("owned") && result.json().get("owned").getAsBoolean());
    }

    public boolean finishBackupJob(long jobId, String serverName, boolean success, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("serverName", serverName);
        body.addProperty("success", success);
        if (message != null) {
            body.addProperty("message", abbreviate(message, 250));
        }
        return backupCall("POST", "/world-backups/jobs/" + jobId + "/finish", body).ok();
    }

    public BackupStartResult startBackupUpload(String worldName, String kind, String serverName, long partSizeBytes, String fingerprint) {
        JsonObject body = new JsonObject();
        body.addProperty("kind", kind);
        body.addProperty("serverName", serverName);
        body.addProperty("partSizeBytes", partSizeBytes);
        if (fingerprint != null) {
            body.addProperty("fingerprint", fingerprint);
        }
        BackupApiResult result = backupCall("POST", "/world-backups/" + encode(worldName) + "/start", body);
        if (!result.ok()) {
            return new BackupStartResult(false, false, 0L, result.detail());
        }
        boolean skipped = getBoolean(result.json(), "skipped", false);
        return new BackupStartResult(true, skipped, getLong(result.json(), "backupId", 0L), "");
    }

    public Optional<String> requestBackupPartUrl(long backupId, int partNumber) {
        JsonObject body = new JsonObject();
        body.addProperty("partNumber", partNumber);
        BackupApiResult result = backupCall("POST", "/world-backups/items/" + backupId + "/part-url", body);
        if (!result.ok()) {
            return Optional.empty();
        }
        String url = getString(result.json(), "url", "");
        return url.isBlank() ? Optional.empty() : Optional.of(url);
    }

    public boolean completeBackupUpload(long backupId, List<BackupPart> parts, long sizeBytes, String sha256) {
        JsonArray partValues = new JsonArray();
        for (BackupPart part : parts) {
            JsonObject partJson = new JsonObject();
            partJson.addProperty("partNumber", part.partNumber());
            partJson.addProperty("etag", part.etag());
            partValues.add(partJson);
        }
        JsonObject body = new JsonObject();
        body.add("parts", partValues);
        body.addProperty("sizeBytes", sizeBytes);
        body.addProperty("sha256", sha256);
        return backupCall("POST", "/world-backups/items/" + backupId + "/complete", body).ok();
    }

    public void abortBackupUpload(long backupId) {
        backupCall("POST", "/world-backups/items/" + backupId + "/abort", new JsonObject());
    }

    public Optional<BackupDownload> requestBackupDownload(long backupId) {
        BackupApiResult result = backupCall("POST", "/world-backups/items/" + backupId + "/download", new JsonObject());
        if (!result.ok()) {
            return Optional.empty();
        }
        String url = getString(result.json(), "url", "");
        if (url.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new BackupDownload(
            url,
            getLong(result.json(), "sizeBytes", 0L),
            getString(result.json(), "sha256", "")
        ));
    }

    private BackupApiResult backupCall(String method, String path, JsonObject body) {
        try {
            ApiResponse response = request(method, path, body == null ? null : gson.toJson(body));
            return new BackupApiResult(response.statusCode(), parseObject(response.body()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new BackupApiResult(0, detailObject("interrupted"));
        } catch (Exception ex) {
            warnThrottled("backup-call:" + path.replaceAll("\\d+", "#"), method + " " + path + " fehlgeschlagen: " + ex.getMessage());
            return new BackupApiResult(0, detailObject("api-unreachable"));
        }
    }

    private List<BackupJob> backupJobList(BackupApiResult result, String label) {
        List<BackupJob> jobs = new ArrayList<>();
        if (!result.ok()) {
            if (result.statusCode() != 0) {
                warnThrottled("backup-jobs:" + label, "Backup-Aufträge (" + label + ") API Fehler: HTTP " + result.statusCode());
            }
            return jobs;
        }
        for (JsonObject row : objectRows(result.json(), "jobs")) {
            jobs.add(mapBackupJob(row));
        }
        return jobs;
    }

    private BackupJob mapBackupJob(JsonObject row) {
        return new BackupJob(
            getLong(row, "id", 0L),
            getString(row, "worldName", ""),
            getString(row, "jobType", "backup"),
            getString(row, "kind", "auto"),
            getLong(row, "backupId", 0L),
            getNullableString(row, "requestedBy"),
            getString(row, "status", "queued"),
            getNullableString(row, "message"),
            getInt(row, "attempts", 0),
            getNullableString(row, "cloneTarget"),
            getNullableString(row, "cloneSource")
        );
    }

    private List<JsonObject> objectRows(JsonObject json, String key) {
        List<JsonObject> rows = new ArrayList<>();
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            return rows;
        }
        for (JsonElement element : json.getAsJsonArray(key)) {
            if (element.isJsonObject()) {
                rows.add(element.getAsJsonObject());
            }
        }
        return rows;
    }

    private JsonObject detailObject(String detail) {
        JsonObject json = new JsonObject();
        json.addProperty("detail", detail);
        return json;
    }

    private List<WorldEntry> listWorlds(String path, String label) {
        return fetchWorlds(path, label).orElseGet(ArrayList::new);
    }

    private Optional<List<WorldEntry>> fetchWorlds(String path, String label) {
        List<WorldEntry> entries = new ArrayList<>();
        try {
            ApiResponse response = request("GET", path, null);
            if (response.statusCode() / 100 != 2) {
                warnThrottled("list-worlds:" + label, "Fehler beim Laden " + label + " via API: HTTP " + response.statusCode());
                return Optional.empty();
            }

            JsonObject json = parseObject(response.body());
            JsonArray worlds = json.has("worlds") && json.get("worlds").isJsonArray()
                ? json.getAsJsonArray("worlds")
                : new JsonArray();
            for (JsonElement worldElement : worlds) {
                if (worldElement.isJsonObject()) {
                    entries.add(mapEntry(worldElement.getAsJsonObject()));
                }
            }
        } catch (Exception ex) {
            warnThrottled("list-worlds-ex:" + label, "Fehler beim Laden " + label + " via API: " + ex.getMessage());
            return Optional.empty();
        }
        return Optional.of(entries);
    }

    private void warnThrottled(String key, String message) {
        long now = System.currentTimeMillis();
        long nextAllowed = warningCooldowns.getOrDefault(key, 0L);
        if (nextAllowed > now) {
            return;
        }
        warningCooldowns.put(key, now + WARNING_COOLDOWN_MS);
        plugin.getLogger().warning(message);
    }

    private void postOrWarn(String path, JsonObject body) {
        try {
            ApiResponse response = request("POST", path, gson.toJson(body));
            if (response.statusCode() / 100 != 2) {
                plugin.getLogger().warning("POST " + path + " fehlgeschlagen: HTTP " + response.statusCode());
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("POST " + path + " fehlgeschlagen: " + ex.getMessage());
        }
    }

    private boolean patchOrWarn(String path, JsonObject body) {
        try {
            ApiResponse response = request("PATCH", path, gson.toJson(body));
            if (response.statusCode() / 100 != 2) {
                plugin.getLogger().warning("PATCH " + path + " fehlgeschlagen: HTTP " + response.statusCode());
                return false;
            }
            return true;
        } catch (Exception ex) {
            plugin.getLogger().warning("PATCH " + path + " fehlgeschlagen: " + ex.getMessage());
            return false;
        }
    }

    private ApiResponse request(String method, String path, String jsonBody) throws IOException, InterruptedException {
        return request(apiBaseUrl, apiToken, method, path, jsonBody);
    }

    private ApiResponse request(String baseUrl, String token, String method, String path, String jsonBody) throws IOException, InterruptedException {
        // A read on the server thread stalls every tick (all players, all worlds) while it waits, so it gets less time.
        Duration timeout = requestTimeout;
        if ("GET".equals(method) && Bukkit.isPrimaryThread()) {
            Duration cap = Duration.ofMillis(Math.max(200L,
                plugin.getConfig().getLong("api.main-thread-timeout-ms", DEFAULT_MAIN_THREAD_READ_TIMEOUT_MS)));
            if (cap.compareTo(timeout) < 0) {
                timeout = cap;
            }
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(trimTrailingSlash(baseUrl) + path))
            .timeout(timeout)
            .header("Authorization", "Bearer " + token)
            .header("X-API-Token", token)
            .header("Accept", "application/json");

        switch (method) {
            case "GET" -> builder.GET();
            case "DELETE" -> builder.DELETE();
            default -> {
                String payload = jsonBody == null ? "{}" : jsonBody;
                builder.header("Content-Type", "application/json");
                builder.method(method, HttpRequest.BodyPublishers.ofString(payload));
            }
        }

        ApiCircuit circuit = circuits.computeIfAbsent(trimTrailingSlash(baseUrl), key -> new ApiCircuit());
        if (circuit.isOpen()) {
            throw new IOException("API nicht erreichbar, nächster Versuch in " + circuit.remainingSeconds() + "s");
        }

        HttpClient client = httpClient;
        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            if (ex instanceof HttpTimeoutException) {
                resetHttpClient(client);
            }
            if (circuit.recordFailure()) {
                plugin.getLogger().warning("API " + baseUrl + " antwortet nicht (" + ex.getMessage()
                    + ") - Requests werden für " + (CIRCUIT_OPEN_MS / 1000) + "s sofort abgebrochen, damit der Server nicht blockiert.");
            }
            throw ex;
        }
        if (circuit.recordSuccess()) {
            plugin.getLogger().info("API " + baseUrl + " ist wieder erreichbar.");
        }
        return new ApiResponse(response.statusCode(), response.body() == null ? "" : response.body());
    }

    private String resolveOrdersApiBaseUrl() {
        String profileBaseUrl = plugin.getConfig().getString("api.profile-base-url", "");
        if (profileBaseUrl != null && !profileBaseUrl.isBlank()) {
            return profileBaseUrl;
        }
        return apiBaseUrl;
    }

    private String resolveOrdersApiToken() {
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
        return apiToken;
    }

    private WorldEntry mapEntry(JsonObject row) {
        return new WorldEntry(
            getString(row, "worldName", ""),
            getString(row, "ownerUuid", ""),
            getString(row, "ownerName", ""),
            getNullableString(row, "orderLabel"),
            getNullableString(row, "ticketOrderId"),
            getNullableString(row, "sourceType"),
            getNullableString(row, "serverName"),
            row.has("builderUserId") && !row.get("builderUserId").isJsonNull() ? row.get("builderUserId").getAsInt() : null,
            getStringList(row, "customers"),
            getStringList(row, "invitedPlayers"),
            getStringList(row, "trustedPlayers"),
            getString(row, "displayName", ""),
            getString(row, "iconMaterial", "GRASS_BLOCK"),
            getBoolean(row, "isPublic", true),
            getBoolean(row, "isArchived", false),
            getNullableString(row, "archivedAt"),
            getNullableDouble(row, "spawnX"),
            getNullableDouble(row, "spawnY"),
            getNullableDouble(row, "spawnZ"),
            getNullableFloat(row, "spawnYaw"),
            getNullableFloat(row, "spawnPitch"),
            getStringMap(row, "gameModes")
        );
    }

    private Map<String, String> getStringMap(JsonObject obj, String key) {
        Map<String, String> out = new HashMap<>();
        if (obj.has(key) && obj.get(key).isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : obj.getAsJsonObject(key).entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    out.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        return out;
    }

    /** Ergebnis von {@link #setWorldGameMode}: ok, oder API kennt den Endpunkt noch nicht (alt). */
    public enum GameModeUpdate { OK, API_OUTDATED, FAILED }

    /**
     * Spielmodus in einer privaten Welt festlegen.
     * @param player Spielername oder "*" für alle, die nicht einzeln eingestellt sind
     * @param mode   SURVIVAL, CREATIVE, SPECTATOR ... oder null = Einstellung entfernen
     */
    public GameModeUpdate setWorldGameMode(String worldName, String player, String mode) {
        JsonObject body = new JsonObject();
        body.addProperty("player", player);
        if (mode == null) {
            body.add("mode", null);
        } else {
            body.addProperty("mode", mode);
        }
        try {
            ApiResponse response = request("PATCH", "/worlds/" + encode(worldName) + "/game-mode", gson.toJson(body));
            if (response.statusCode() / 100 == 2) {
                return GameModeUpdate.OK;
            }
            plugin.getLogger().warning("Spielmodus für " + worldName + " nicht gespeichert: HTTP " + response.statusCode());
            return response.statusCode() == 404 || response.statusCode() == 405 ? GameModeUpdate.API_OUTDATED : GameModeUpdate.FAILED;
        } catch (Exception ex) {
            plugin.getLogger().warning("Spielmodus für " + worldName + " nicht gespeichert: " + ex.getMessage());
            return GameModeUpdate.FAILED;
        }
    }

    private JsonObject parseObject(String raw) {
        JsonElement element = gson.fromJson(raw == null || raw.isBlank() ? "{}" : raw, JsonElement.class);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private List<String> getStringList(JsonObject obj, String key) {
        List<String> out = new ArrayList<>();
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            return out;
        }
        JsonArray arr = obj.getAsJsonArray(key);
        for (JsonElement item : arr) {
            if (item.isJsonPrimitive()) {
                out.add(item.getAsString());
            }
        }
        return out;
    }

    private String getString(JsonObject obj, String key, String fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return fallback;
        }
        return obj.get(key).getAsString();
    }

    private String getNullableString(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        return obj.get(key).getAsString();
    }

    private boolean getBoolean(JsonObject obj, String key, boolean fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return fallback;
        }
        return obj.get(key).getAsBoolean();
    }

    private int getInt(JsonObject obj, String key, int fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return fallback;
        }
        return obj.get(key).getAsInt();
    }

    private long getLong(JsonObject obj, String key, long fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return fallback;
        }
        return obj.get(key).getAsLong();
    }

    private Double getNullableDouble(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        return obj.get(key).getAsDouble();
    }

    private Float getNullableFloat(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        return obj.get(key).getAsFloat();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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

    private String resolveLocalServerId() {
        for (String envKey : List.of(
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

    private String abbreviate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLen - 3)) + "...";
    }

    private record ApiResponse(int statusCode, String body) {
    }

    public record JoinRequest(long id, String playerName, String worldName) {
    }

    public record BackupEntry(long id, String kind, long sizeBytes, String createdAt, String sha256) {
    }

    public record BackupJob(
        long id,
        String worldName,
        String jobType,
        String kind,
        long backupId,
        String requestedBy,
        String status,
        String message,
        int attempts,
        /** Backup-Auftrag, der ein Duplikat vorbereitet: Name der Zielwelt (sonst null). */
        String cloneTarget,
        /** Restore-Auftrag eines Duplikats: Name der Quellwelt (sonst null). */
        String cloneSource
    ) {
        public boolean isClonePart() {
            return cloneTarget != null || cloneSource != null;
        }
    }

    public record WorldBackupOverview(int manualLimit, List<BackupEntry> backups, List<BackupJob> jobs) {
    }

    public record BackupPart(int partNumber, String etag) {
    }

    public record BackupDownload(String url, long sizeBytes, String sha256) {
    }

    public record BackupStartResult(boolean ok, boolean skipped, long backupId, String error) {
    }

    public record JobHeartbeat(boolean reachable, boolean owned) {
    }

    public record BackupApiResult(int statusCode, JsonObject json) {
        public boolean ok() {
            return statusCode / 100 == 2;
        }

        /** Fehlercode der API (z.B. "manual-limit", "already-queued"), leer wenn keiner vorhanden. */
        public String detail() {
            if (json == null || !json.has("detail") || !json.get("detail").isJsonPrimitive()) {
                return statusCode == 0 ? "api-unreachable" : "";
            }
            return json.get("detail").getAsString();
        }
    }

    /**
     * Circuit-Breaker: Nach mehreren Netzwerkfehlern in Folge werden Requests eine Zeit lang sofort abgelehnt,
     * statt jeden Aufrufer (oft den Main-Thread) bis zum Timeout blockieren zu lassen.
     */
    private static final class ApiCircuit {
        private int consecutiveFailures;
        private long openUntilMillis;

        synchronized boolean isOpen() {
            return System.currentTimeMillis() < openUntilMillis;
        }

        synchronized long remainingSeconds() {
            return Math.max(1, (openUntilMillis - System.currentTimeMillis() + 999) / 1000);
        }

        /** @return true, wenn der Circuit durch diesen Fehler (erneut) geöffnet wurde und zuvor geschlossen war. */
        synchronized boolean recordFailure() {
            consecutiveFailures++;
            if (consecutiveFailures < CIRCUIT_FAILURE_THRESHOLD) {
                return false;
            }
            boolean wasClosed = consecutiveFailures == CIRCUIT_FAILURE_THRESHOLD;
            openUntilMillis = System.currentTimeMillis() + CIRCUIT_OPEN_MS;
            return wasClosed;
        }

        /** @return true, wenn der Circuit vorher offen war (API wieder erreichbar). */
        synchronized boolean recordSuccess() {
            boolean wasOpen = consecutiveFailures >= CIRCUIT_FAILURE_THRESHOLD;
            consecutiveFailures = 0;
            openUntilMillis = 0L;
            return wasOpen;
        }
    }
}
