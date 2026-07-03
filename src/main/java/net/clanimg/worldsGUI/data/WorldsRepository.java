package net.clanimg.worldsGUI.data;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.clanimg.worldsGUI.model.WorldEntry;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

public final class WorldsRepository {
    private final JavaPlugin plugin;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private String lastInitializeError = "Unbekannter Fehler";

    public WorldsRepository(JavaPlugin plugin, String host, int port, String database, String username, String password) {
        this.plugin = plugin;
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
    }

    public boolean initialize() {
        String sql = """
            CREATE TABLE IF NOT EXISTS worldsgui_worlds (
                world_name VARCHAR(64) PRIMARY KEY,
                owner_uuid VARCHAR(36) NOT NULL,
                owner_name VARCHAR(16) NOT NULL,
                order_label VARCHAR(16) NULL,
                invited_players TEXT NULL,
                trusted_players TEXT NULL,
                world_index INT NOT NULL,
                display_name VARCHAR(64) NOT NULL,
                icon_material VARCHAR(64) NOT NULL,
                is_public BOOLEAN NOT NULL DEFAULT TRUE,
                spawn_x DOUBLE NULL,
                spawn_y DOUBLE NULL,
                spawn_z DOUBLE NULL,
                spawn_yaw FLOAT NULL,
                spawn_pitch FLOAT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_owner_uuid (owner_uuid),
                INDEX idx_public (is_public)
            )
            """;
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
            ensureOrderLabelColumn(connection);
            ensureInvitedPlayersColumn(connection);
            ensureTrustedPlayersColumn(connection);
            ensurePlayerPresenceTable(connection);
            ensureJoinRequestsTable(connection);
            lastInitializeError = "";
            return true;
        } catch (SQLException ex) {
            lastInitializeError = ex.getMessage();
            plugin.getLogger().severe("Fehler beim Initialisieren der DB (" + host + ":" + port + "/" + database + "): " + ex.getMessage());
            return false;
        }
    }

    public String lastInitializeError() {
        return lastInitializeError == null || lastInitializeError.isBlank() ? "Unbekannter Fehler" : lastInitializeError;
    }

    public int nextWorldIndex(String ownerUuid) {
        String sql = "SELECT COALESCE(MAX(world_index), 0) + 1 AS next_idx FROM worldsgui_worlds WHERE owner_uuid = ?";
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerUuid);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("next_idx");
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().severe("Fehler beim Ermitteln des Weltindex: " + ex.getMessage());
        }
        return 1;
    }

    public void insertWorld(
        String worldName,
        String ownerUuid,
        String ownerName,
        String orderLabel,
        int worldIndex,
        String displayName,
        String iconMaterial,
        boolean isPublic
    ) {
        String sql = """
            INSERT INTO worldsgui_worlds
            (world_name, owner_uuid, owner_name, order_label, invited_players, trusted_players, world_index, display_name, icon_material, is_public)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, worldName);
            statement.setString(2, ownerUuid);
            statement.setString(3, ownerName);
            statement.setString(4, orderLabel);
            statement.setString(5, "");
            statement.setString(6, "");
            statement.setInt(7, worldIndex);
            statement.setString(8, displayName);
            statement.setString(9, iconMaterial);
            statement.setBoolean(10, isPublic);
            statement.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().severe("Fehler beim Speichern der Welt: " + ex.getMessage());
        }
    }

    public void setInvitedPlayers(String worldName, List<String> players) {
        updateSingleField(worldName, "invited_players", joinPlayers(players));
    }

    public void setTrustedPlayers(String worldName, List<String> players) {
        updateSingleField(worldName, "trusted_players", joinPlayers(players));
    }

    public Optional<WorldEntry> findByWorldName(String worldName) {
        String sql = "SELECT * FROM worldsgui_worlds WHERE world_name = ?";
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, worldName);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapEntry(rs));
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().severe("Fehler beim Laden der Weltdaten: " + ex.getMessage());
        }
        return Optional.empty();
    }

    public List<WorldEntry> listOwnWorlds(String ownerUuid) {
        String sql = "SELECT * FROM worldsgui_worlds WHERE owner_uuid = ? ORDER BY created_at DESC";
        List<WorldEntry> entries = new ArrayList<>();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerUuid);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    entries.add(mapEntry(rs));
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().severe("Fehler beim Laden eigener Welten: " + ex.getMessage());
        }
        return entries;
    }

    public List<WorldEntry> listDiscoverableWorlds(String viewerUuid, boolean admin) {
        String sql = admin
            ? "SELECT * FROM worldsgui_worlds WHERE owner_uuid <> ? ORDER BY created_at DESC"
            : "SELECT * FROM worldsgui_worlds WHERE owner_uuid <> ? AND is_public = TRUE ORDER BY created_at DESC";
        List<WorldEntry> entries = new ArrayList<>();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, viewerUuid);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    entries.add(mapEntry(rs));
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().severe("Fehler beim Laden öffentlicher Welten: " + ex.getMessage());
        }
        return entries;
    }

    public List<WorldEntry> listInvitedWorlds(String playerName) {
        String sql = "SELECT * FROM worldsgui_worlds ORDER BY created_at DESC";
        List<WorldEntry> entries = new ArrayList<>();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    WorldEntry entry = mapEntry(rs);
                    if (containsIgnoreCase(entry.invitedPlayers(), playerName)) {
                        entries.add(entry);
                    }
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().severe("Fehler beim Laden eingeladener Welten: " + ex.getMessage());
        }
        return entries;
    }

    public void setPublic(String worldName, boolean value) {
        updateSingleField(worldName, "is_public", value);
    }

    public void setDisplayName(String worldName, String value) {
        updateSingleField(worldName, "display_name", value);
    }

    public void setIcon(String worldName, String value) {
        updateSingleField(worldName, "icon_material", value);
    }

    public void setSpawn(String worldName, Location loc) {
        String sql = """
            UPDATE worldsgui_worlds
            SET spawn_x = ?, spawn_y = ?, spawn_z = ?, spawn_yaw = ?, spawn_pitch = ?
            WHERE world_name = ?
            """;
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setDouble(1, loc.getX());
            statement.setDouble(2, loc.getY());
            statement.setDouble(3, loc.getZ());
            statement.setFloat(4, loc.getYaw());
            statement.setFloat(5, loc.getPitch());
            statement.setString(6, worldName);
            statement.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().severe("Fehler beim Speichern des Spawns: " + ex.getMessage());
        }
    }

    public void deleteWorld(String worldName) {
        String sql = "DELETE FROM worldsgui_worlds WHERE world_name = ?";
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, worldName);
            statement.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().severe("Fehler beim Löschen der Welt in DB: " + ex.getMessage());
        }
    }

    public void upsertPlayerPresence(String playerName, String currentWorld, boolean online) {
        String sql = """
            INSERT INTO worldsgui_player_presence (player_name, is_online, current_world, updated_at)
            VALUES (?, ?, ?, CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE
                is_online = VALUES(is_online),
                current_world = VALUES(current_world),
                updated_at = CURRENT_TIMESTAMP
            """;
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerName);
            statement.setBoolean(2, online);
            statement.setString(3, currentWorld);
            statement.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().warning("Fehler beim Aktualisieren der Presence: " + ex.getMessage());
        }
    }

    public List<JoinRequest> listPendingJoinRequests(int limit) {
        String sql = """
            SELECT id, player_name, world_name
            FROM worldsgui_join_requests
            WHERE status = 'pending'
            ORDER BY created_at ASC
            LIMIT ?
            """;
        List<JoinRequest> requests = new ArrayList<>();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, Math.max(1, limit));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    requests.add(new JoinRequest(rs.getLong("id"), rs.getString("player_name"), rs.getString("world_name")));
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Fehler beim Laden der Join-Requests: " + ex.getMessage());
        }
        return requests;
    }

    public void markJoinRequest(long id, String status, String message) {
        String sql = """
            UPDATE worldsgui_join_requests
            SET status = ?,
                result_message = ?,
                processed_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """;
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, message);
            statement.setLong(3, id);
            statement.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().warning("Fehler beim Aktualisieren eines Join-Requests: " + ex.getMessage());
        }
    }

    private void updateSingleField(String worldName, String field, Object value) {
        String sql = "UPDATE worldsgui_worlds SET " + field + " = ? WHERE world_name = ?";
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            statement.setString(2, worldName);
            statement.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().severe("Fehler beim Aktualisieren von " + field + ": " + ex.getMessage());
        }
    }

    private WorldEntry mapEntry(ResultSet rs) throws SQLException {
        return new WorldEntry(
            rs.getString("world_name"),
            rs.getString("owner_uuid"),
            rs.getString("owner_name"),
            rs.getString("order_label"),
            splitPlayers(rs.getString("invited_players")),
            splitPlayers(rs.getString("trusted_players")),
            rs.getString("display_name"),
            rs.getString("icon_material"),
            rs.getBoolean("is_public"),
            rs.getObject("spawn_x") == null ? null : rs.getDouble("spawn_x"),
            rs.getObject("spawn_y") == null ? null : rs.getDouble("spawn_y"),
            rs.getObject("spawn_z") == null ? null : rs.getDouble("spawn_z"),
            rs.getObject("spawn_yaw") == null ? null : rs.getFloat("spawn_yaw"),
            rs.getObject("spawn_pitch") == null ? null : rs.getFloat("spawn_pitch")
        );
    }

    private void ensureOrderLabelColumn(Connection connection) {
        String sql = "ALTER TABLE worldsgui_worlds ADD COLUMN order_label VARCHAR(16) NULL AFTER owner_name";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        } catch (SQLException ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            if (!msg.contains("duplicate") && !msg.contains("exists")) {
                plugin.getLogger().warning("Konnte order_label nicht anlegen: " + ex.getMessage());
            }
        }
    }

    private void ensureInvitedPlayersColumn(Connection connection) {
        String sql = "ALTER TABLE worldsgui_worlds ADD COLUMN invited_players TEXT NULL AFTER order_label";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        } catch (SQLException ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            if (!msg.contains("duplicate") && !msg.contains("exists")) {
                plugin.getLogger().warning("Konnte invited_players nicht anlegen: " + ex.getMessage());
            }
        }
    }

    private void ensureTrustedPlayersColumn(Connection connection) {
        String sql = "ALTER TABLE worldsgui_worlds ADD COLUMN trusted_players TEXT NULL AFTER invited_players";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        } catch (SQLException ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            if (!msg.contains("duplicate") && !msg.contains("exists")) {
                plugin.getLogger().warning("Konnte trusted_players nicht anlegen: " + ex.getMessage());
            }
        }
    }

    private void ensurePlayerPresenceTable(Connection connection) {
        String sql = """
            CREATE TABLE IF NOT EXISTS worldsgui_player_presence (
                player_name VARCHAR(16) PRIMARY KEY,
                is_online BOOLEAN NOT NULL DEFAULT FALSE,
                current_world VARCHAR(64) NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_presence_online (is_online)
            )
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        } catch (SQLException ex) {
            plugin.getLogger().warning("Konnte worldsgui_player_presence nicht anlegen: " + ex.getMessage());
        }
    }

    private void ensureJoinRequestsTable(Connection connection) {
        String sql = """
            CREATE TABLE IF NOT EXISTS worldsgui_join_requests (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                player_name VARCHAR(16) NOT NULL,
                world_name VARCHAR(64) NOT NULL,
                status VARCHAR(20) NOT NULL DEFAULT 'pending',
                result_message VARCHAR(255) NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                processed_at TIMESTAMP NULL,
                INDEX idx_join_requests_status_created (status, created_at)
            )
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        } catch (SQLException ex) {
            plugin.getLogger().warning("Konnte worldsgui_join_requests nicht anlegen: " + ex.getMessage());
        }
    }

    private List<String> splitPlayers(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        String[] tokens = raw.split(",");
        List<String> out = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            String trimmed = token.trim();
            if (!trimmed.isBlank()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private String joinPlayers(List<String> players) {
        StringBuilder builder = new StringBuilder();
        for (String player : players) {
            if (player == null) {
                continue;
            }
            String trimmed = player.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(trimmed);
        }
        return builder.toString();
    }

    private boolean containsIgnoreCase(List<String> values, String needle) {
        for (String value : values) {
            if (value.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
    }

    private Connection openConnection() throws SQLException {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
        } catch (ClassNotFoundException ex) {
            throw new SQLException("MariaDB-Treiber nicht gefunden", ex);
        }

        String url = "jdbc:mariadb://" + host + ":" + port + "/" + database + "?useUnicode=true&characterEncoding=utf8";
        return DriverManager.getConnection(url, username, password);
    }

    public record JoinRequest(long id, String playerName, String worldName) {
    }
}
