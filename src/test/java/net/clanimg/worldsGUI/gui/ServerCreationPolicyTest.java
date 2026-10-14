package net.clanimg.worldsGUI.gui;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerCreationPolicyTest {
    private static final List<String> BLOCKED = List.of("lobby-1", "lobby-2", "plotwelt");

    @Test
    void blocksEachConfiguredServerWithCaseAndWhitespaceIgnored() {
        for (String server : BLOCKED) {
            assertFalse(ServerCreationPolicy.allowsLocalCreation(server, server, BLOCKED));
            assertTrue(ServerCreationPolicy.isBlocked(" " + server.toUpperCase() + " ", BLOCKED));
        }
    }

    @Test
    void cloudInstancesAreDistinctServers() {
        assertFalse(ServerCreationPolicy.sameServer("build-1", "build-2"));
        assertFalse(ServerCreationPolicy.sameServer("lobby-1", "lobby-2"));
        assertFalse(ServerCreationPolicy.isBlocked("lobby-3", BLOCKED));
        assertTrue(ServerCreationPolicy.sameServer(" BUILD-1 ", "build-1"));
    }

    @Test
    void unidentifiedContainerCannotBypassBlacklist() {
        assertFalse(ServerCreationPolicy.allowsLocalCreation("container-abc", "", BLOCKED));
        assertFalse(ServerCreationPolicy.allowsLocalCreation("", "", BLOCKED));
        assertFalse(ServerCreationPolicy.sameServer(null, "build-1"));
        assertFalse(ServerCreationPolicy.sameServer("", ""));
    }

    @Test
    void allowsIdentifiedBuildServerAndUnrestrictedHostnameFallback() {
        assertTrue(ServerCreationPolicy.allowsLocalCreation("build-1", "build-1", BLOCKED));
        assertTrue(ServerCreationPolicy.allowsLocalCreation("container-abc", "", List.of()));
    }
}
