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
        int worldIndex,
        String displayName,
        String iconMaterial,
        boolean isPublic
    ) {
        String sql = """
            INSERT INTO worldsgui_worlds
            (world_name, owner_uuid, owner_name, world_index, display_name, icon_material, is_public)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, worldName);
            statement.setString(2, ownerUuid);
            statement.setString(3, ownerName);
            statement.setInt(4, worldIndex);
            statement.setString(5, displayName);
            statement.setString(6, iconMaterial);
            statement.setBoolean(7, isPublic);
            statement.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().severe("Fehler beim Speichern der Welt: " + ex.getMessage());
        }
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

    private Connection openConnection() throws SQLException {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
        } catch (ClassNotFoundException ex) {
            throw new SQLException("MariaDB-Treiber nicht gefunden", ex);
        }

        String url = "jdbc:mariadb://" + host + ":" + port + "/" + database + "?useUnicode=true&characterEncoding=utf8";
        return DriverManager.getConnection(url, username, password);
    }
}
