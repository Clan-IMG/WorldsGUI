package net.clanimg.worldsGUI.guiruntime;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Verwaltet eine {@link PlayerGuiSession} pro Online-Spieler. */
public final class PlayerSessionManager {
    private final Map<UUID, PlayerGuiSession> sessions = new ConcurrentHashMap<>();

    public PlayerGuiSession getOrCreate(UUID playerId) {
        return sessions.computeIfAbsent(playerId, id -> new PlayerGuiSession());
    }

    public void remove(UUID playerId) {
        sessions.remove(playerId);
    }

    public void clear() {
        sessions.clear();
    }
}
