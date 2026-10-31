package net.clanimg.worldsGUI.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.World;

public record WorldEntry(
    String worldName,
    String ownerUuid,
    String ownerName,
    String orderLabel,
    String ticketOrderId,
    String sourceType,
    String serverName,
    Integer builderUserId,
    List<String> customers,
    List<String> invitedPlayers,
    List<String> trustedPlayers,
    String displayName,
    String iconMaterial,
    boolean isPublic,
    boolean isArchived,
    String archivedAt,
    Double spawnX,
    Double spawnY,
    Double spawnZ,
    Float spawnYaw,
    Float spawnPitch,
    /** Spielmodus je Spieler (Name klein) bzw. "*" für alle anderen - nur private Welten. */
    Map<String, String> gameModes
) {
    public WorldEntry {
        gameModes = gameModes == null ? Map.of() : Map.copyOf(gameModes);
    }

    public WorldEntry(String worldName, String ownerUuid, String ownerName, String orderLabel, String ticketOrderId,
                      String sourceType, String serverName, Integer builderUserId, List<String> customers,
                      List<String> invitedPlayers, List<String> trustedPlayers, String displayName, String iconMaterial,
                      boolean isPublic, boolean isArchived, String archivedAt, Double spawnX, Double spawnY, Double spawnZ,
                      Float spawnYaw, Float spawnPitch) {
        this(worldName, ownerUuid, ownerName, orderLabel, ticketOrderId, sourceType, serverName, builderUserId, customers,
            invitedPlayers, trustedPlayers, displayName, iconMaterial, isPublic, isArchived, archivedAt, spawnX, spawnY,
            spawnZ, spawnYaw, spawnPitch, Map.of());
    }

    public Optional<Location> toSpawnLocation(World world) {
        if (spawnX == null || spawnY == null || spawnZ == null) {
            return Optional.empty();
        }
        return Optional.of(new Location(world, spawnX, spawnY, spawnZ, spawnYaw == null ? 0f : spawnYaw, spawnPitch == null ? 0f : spawnPitch));
    }
}
