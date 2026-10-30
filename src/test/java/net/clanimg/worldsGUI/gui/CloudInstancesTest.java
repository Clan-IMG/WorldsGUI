package net.clanimg.worldsGUI.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CloudInstancesTest {
    /** Aufbau wie ModelsListServersResponse / ModelsServerSummary aus dem SimpleCloud-API-Client. */
    private static final String SERVERS = """
        {"count": 6, "servers": [
          {"server_id": "srv-l2", "numerical_id": 2, "state": "AVAILABLE", "player_count": 3, "max_players": 50,
           "server_group": {"id": "g-lobby", "name": "lobby"}},
          {"server_id": "srv-l1", "numerical_id": 1, "state": "INGAME", "player_count": 0, "max_players": 50,
           "server_group": {"id": "g-lobby", "name": "lobby"}},
          {"server_id": "srv-l3", "numerical_id": 3, "state": "STARTING", "player_count": 0, "max_players": 50,
           "server_group": {"id": "g-lobby", "name": "lobby"}},
          {"server_id": "srv-proxy", "numerical_id": 1, "state": "AVAILABLE", "player_count": 3, "max_players": 500,
           "server_group": {"id": "g-proxy", "name": "proxy"}},
          {"server_id": "srv-build", "numerical_id": -1, "state": "AVAILABLE", "player_count": 1, "max_players": 50,
           "persistent_server_id": "build", "server_group": null},
          {"server_id": "srv-weird", "state": "AVAILABLE", "server_group": {"name": "lobby"}}
        ]}
        """;

    @Test
    void listsOnlyJoinableInstancesOfTheGroupSortedByNumber() {
        List<CloudInstances.Instance> instances = CloudInstances.parse(SERVERS, "Lobby", "build", -1);

        assertEquals(List.of(
            new CloudInstances.Instance("srv-l1", "lobby-1", 1, 0, 50, false),
            new CloudInstances.Instance("srv-l2", "lobby-2", 2, 3, 50, false)), instances);
    }

    @Test
    void marksTheInstanceThePlayerIsOn() {
        List<CloudInstances.Instance> instances = CloudInstances.parse(SERVERS, "lobby", "lobby", 2);

        assertEquals("lobby-2", instances.stream().filter(CloudInstances.Instance::current).findFirst().orElseThrow().name());
    }

    @Test
    void persistentServersAreNoTemplates() {
        assertTrue(CloudInstances.parse(SERVERS, "build", "dev", -1).isEmpty());
    }
}
