package net.clanimg.worldsGUI.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Laufende Instanzen einer SimpleCloud-Servergruppe (Template), z.B. lobby-1 und lobby-2 der Gruppe "lobby".
 * Eine Welt mit "lobby" als Server liegt auf jeder Instanz gleich - der Spieler wählt, auf welche er geht.
 *
 * <p>Grundlage ist {@code GET /v0/servers} des Controllers; die Feldnamen entsprechen ModelsServerSummary aus dem
 * SimpleCloud-API-Client (cloud-api-paper). Persistente Server (persistent_server_id gesetzt) zählen nicht dazu.
 */
final class CloudInstances {
    /** Nur Instanzen, auf die man verbunden werden kann. */
    private static final Set<String> JOINABLE_STATES = Set.of("AVAILABLE", "INGAME");

    /**
     * @param serverId   ID für {@code POST /v0/players/connect} ({@code server_id})
     * @param name       Anzeigename, z.B. lobby-2
     * @param current    der Server, auf dem der Spieler gerade ist
     */
    record Instance(String serverId, String name, int numericalId, int players, int maxPlayers, boolean current) {}

    private CloudInstances() {
    }

    /**
     * @param localGroup       SIMPLECLOUD_GROUP dieses Servers (oder null)
     * @param localNumericalId SIMPLECLOUD_NUMERICAL_ID dieses Servers (-1 bei persistenten Servern)
     */
    static List<Instance> parse(String json, String group, String localGroup, int localNumericalId) {
        List<Instance> instances = new ArrayList<>();
        if (json == null || json.isBlank() || group == null || group.isBlank()) {
            return instances;
        }
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject() || !root.getAsJsonObject().has("servers") || !root.getAsJsonObject().get("servers").isJsonArray()) {
            return instances;
        }
        boolean localIsGroup = localGroup != null && localGroup.trim().equalsIgnoreCase(group.trim());
        for (JsonElement element : root.getAsJsonObject().getAsJsonArray("servers")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject server = element.getAsJsonObject();
            if (!string(server, "persistent_server_id").isBlank()) {
                continue;
            }
            JsonObject serverGroup = server.has("server_group") && server.get("server_group").isJsonObject()
                ? server.getAsJsonObject("server_group")
                : new JsonObject();
            if (!string(serverGroup, "name").equalsIgnoreCase(group.trim())) {
                continue;
            }
            String state = string(server, "state").toUpperCase(Locale.ROOT);
            String serverId = string(server, "server_id");
            int numericalId = number(server, "numerical_id", -1);
            if (!JOINABLE_STATES.contains(state) || serverId.isBlank() || numericalId < 0) {
                continue;
            }
            String name = string(serverGroup, "name") + "-" + numericalId;
            instances.add(new Instance(serverId, name, numericalId, number(server, "player_count", 0),
                number(server, "max_players", 0), localIsGroup && numericalId == localNumericalId));
        }
        instances.sort(Comparator.comparingInt(Instance::numericalId));
        return instances;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? "" : value.getAsString().trim();
    }

    private static int number(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsInt();
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
