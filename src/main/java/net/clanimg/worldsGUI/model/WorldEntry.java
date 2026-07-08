package net.clanimg.worldsGUI.model;

import java.util.List;
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
    Float spawnPitch
) {
    public Optional<Location> toSpawnLocation(World world) {
        if (spawnX == null || spawnY == null || spawnZ == null) {
            return Optional.empty();
        }
        return Optional.of(new Location(world, spawnX, spawnY, spawnZ, spawnYaw == null ? 0f : spawnYaw, spawnPitch == null ? 0f : spawnPitch));
    }
}
