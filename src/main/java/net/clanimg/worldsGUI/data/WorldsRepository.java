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
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.clanimg.worldsGUI.model.WorldEntry;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

public final class WorldsRepository {
    private static final long WARNING_COOLDOWN_MS = 30_000L;

    private final JavaPlugin plugin;
    private final String apiBaseUrl;
    private final String apiToken;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Gson gson;
    private final Map<String, Long> warningCooldowns = new ConcurrentHashMap<>();
    private String lastInitializeError = "Unbekannter Fehler";

    public record OrderAssignmentCheck(boolean exists, boolean assigned) {}
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
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .build();
        this.gson = new Gson();
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
        try {
            ApiResponse response = request("GET", "/worlds/" + encode(worldName), null);
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() / 100 != 2) {
                warnThrottled("findByWorldName", "findByWorldName API Fehler: HTTP " + response.statusCode());
                return Optional.empty();
            }
            JsonObject json = parseObject(response.body());
            JsonObject world = json.has("world") && json.get("world").isJsonObject()
                ? json.getAsJsonObject("world")
                : null;
            if (world == null) {
                return Optional.empty();
            }
            return Optional.of(mapEntry(world));
        } catch (Exception ex) {
            warnThrottled("findByWorldName-ex", "Fehler beim Laden der Weltdaten via API: " + ex.getMessage());
            return Optional.empty();
        }
    }

    public List<WorldEntry> listOwnWorlds(String ownerUuid) {
        return listWorlds("/worlds/owner/" + encode(ownerUuid), "eigener Welten");
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

    public void setSpawn(String worldName, Location loc) {
        JsonObject body = new JsonObject();
        body.addProperty("x", loc.getX());
        body.addProperty("y", loc.getY());
        body.addProperty("z", loc.getZ());
        body.addProperty("yaw", loc.getYaw());
        body.addProperty("pitch", loc.getPitch());
        patchOrWarn("/worlds/" + encode(worldName) + "/spawn", body);
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

    public void upsertServerPresence(String serverName, boolean online, String status, int heartbeatIntervalSeconds) {
        String normalizedServer = serverName == null ? "" : serverName.trim();
        if (normalizedServer.isBlank()) {
            return;
        }

        JsonObject body = new JsonObject();
        body.addProperty("serverName", normalizedServer);
        body.addProperty("online", online);
        body.addProperty("status", status == null ? "" : status);
        body.addProperty("heartbeatIntervalSeconds", Math.max(1, heartbeatIntervalSeconds));

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

    private List<WorldEntry> listWorlds(String path, String label) {
        List<WorldEntry> entries = new ArrayList<>();
        try {
            ApiResponse response = request("GET", path, null);
            if (response.statusCode() / 100 != 2) {
                warnThrottled("list-worlds:" + label, "Fehler beim Laden " + label + " via API: HTTP " + response.statusCode());
                return entries;
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
        }
        return entries;
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
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(trimTrailingSlash(baseUrl) + path))
            .timeout(requestTimeout)
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

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
            getNullableFloat(row, "spawnPitch")
        );
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
}
