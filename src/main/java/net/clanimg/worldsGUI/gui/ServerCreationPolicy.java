package net.clanimg.worldsGUI.gui;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.clanimg.worldsGUI.data.WorldsRepository.KnownServer;

final class ServerCreationPolicy {
    private ServerCreationPolicy() {
    }

    static boolean sameServer(String left, String right) {
        return left != null && right != null && !left.isBlank() && !right.isBlank()
            && left.trim().equalsIgnoreCase(right.trim());
    }

    static boolean isBlocked(String server, List<String> blockedServers) {
        return blockedServers.stream().anyMatch(blocked -> sameServer(blocked, server));
    }

    /**
     * Zielserver fürs Duplizieren auf beliebige Server: jeder Server, auf dem WorldsGUI läuft oder lief ({@code known}),
     * dazu die gerade erreichbaren ({@code online}, nur nötig, wenn {@code known} fehlt) und dieser Server selbst.
     * Groß-/Kleinschreibung wird zusammengefasst, "online" gewinnt; sortiert nach Name.
     */
    static List<KnownServer> duplicateTargets(Optional<List<KnownServer>> known, List<String> online, String explicitLocal) {
        Map<String, KnownServer> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (KnownServer server : known.orElse(List.of())) {
            byName.merge(server.name(), server, (existing, other) -> existing.online() ? existing : other);
        }
        for (String name : online) {
            if (name != null && !name.isBlank()) {
                byName.put(name.trim(), new KnownServer(name.trim(), true));
            }
        }
        if (explicitLocal != null && !explicitLocal.isBlank()) {
            byName.put(explicitLocal.trim(), new KnownServer(explicitLocal.trim(), true));
        }
        return List.copyOf(byName.values());
    }

    static boolean allowsLocalCreation(String localServer, String explicitLocalServer, List<String> blockedServers) {
        // A container hostname or Paper's server name cannot reliably identify a blacklisted cloud server.
        return localServer != null && !localServer.isBlank()
            && (blockedServers.isEmpty() || (explicitLocalServer != null && !explicitLocalServer.isBlank()))
            && !isBlocked(localServer, blockedServers);
    }
}
