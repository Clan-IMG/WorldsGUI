package net.clanimg.worldsGUI.gui;

import java.util.List;
import java.util.Optional;
import net.clanimg.worldsGUI.data.WorldsRepository.KnownServer;
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
    void aGroupNameBlocksAllItsInstances() {
        List<String> blocked = List.of("lobby", "plotwelt");
        assertTrue(ServerCreationPolicy.isBlocked("lobby-2", blocked));
        assertTrue(ServerCreationPolicy.isBlocked("lobby", blocked));
        assertFalse(ServerCreationPolicy.allowsLocalCreation("lobby-1", "lobby-1", blocked));
        assertFalse(ServerCreationPolicy.isBlocked("build", blocked));
        assertFalse(ServerCreationPolicy.isBlocked("build-26-1", blocked));
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

    @Test
    void duplicateTargetsIncludeOfflineServersWithWorldsGuiAndMergeCase() {
        List<KnownServer> known = List.of(
            new KnownServer("redstone", false),
            new KnownServer("Build", false),
            new KnownServer("lobby", true),
            new KnownServer("build-26-1", false));

        List<KnownServer> targets = ServerCreationPolicy.duplicateTargets(Optional.of(known), List.of(), "build");

        assertEquals(List.of(
            new KnownServer("build", true),
            new KnownServer("build-26-1", false),
            new KnownServer("lobby", true),
            new KnownServer("redstone", false)), targets);
    }

    @Test
    void duplicateTargetsFallBackToOnlineServersWhenTheApiHasNoServerList() {
        List<KnownServer> targets = ServerCreationPolicy.duplicateTargets(Optional.empty(), List.of("dev", "lobby"), "");

        assertEquals(List.of(new KnownServer("dev", true), new KnownServer("lobby", true)), targets);
    }
}
