package net.clanimg.worldsGUI.gui;

import java.util.List;

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

    static boolean allowsLocalCreation(String localServer, String explicitLocalServer, List<String> blockedServers) {
        // A container hostname or Paper's server name cannot reliably identify a blacklisted cloud server.
        return localServer != null && !localServer.isBlank()
            && (blockedServers.isEmpty() || (explicitLocalServer != null && !explicitLocalServer.isBlank()))
            && !isBlocked(localServer, blockedServers);
    }
}
