package net.clanimg.worldsGUI.guiconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class GuiConfigLoaderPaginationTest {
    private static final Logger LOGGER = Logger.getLogger("GuiConfigLoaderPaginationTest");

    @Test
    void bundledGuisYamlHasLeftAlignedNavigationSortItemAndChunkyToggle() throws Exception {
        GuiConfig config = GuiConfigLoader.loadFromYaml(bundledYaml(), LOGGER);

        GuiDefinition myWorlds = config.get("my-worlds").orElseThrow();
        assertEquals("invited-worlds", myWorlds.rowSlots().get(1).action().anyClickTrigger().guiId());
        assertEquals("tickets", myWorlds.rowSlots().get(2).action().anyClickTrigger().guiId());
        GuiAction sort = myWorlds.rowSlots().get(3).action();
        assertEquals("nav sort next", sort.leftClickTrigger().command());
        assertEquals("nav sort previous", sort.rightClickTrigger().command());
        assertFalse(sort.leftClickTrigger().chatFeedback(), "sonst schließt sich das Menü beim Sortieren");
        assertFalse(sort.rightClickTrigger().chatFeedback(), "sonst schließt sich das Menü beim Sortieren");
        assertEquals("create-world", myWorlds.rowSlots().get(9).action().anyClickTrigger().guiId());
        assertNull(myWorlds.rowSlots().get(4));
        assertNotNull(myWorlds.pagination());
        assertEquals(7, myWorlds.pagination().previousSlot());
        assertEquals(8, myWorlds.pagination().nextSlot());

        assertTrue(config.get("recent-worlds").isEmpty(), "das eigene GUI für zuletzt besuchte Welten entfällt");

        GuiTrigger chunkyToggle = config.get("edit-world").orElseThrow().rowSlots().get(6).action().anyClickTrigger();
        assertEquals(TriggerType.TOGGLE, chunkyToggle.type());
        assertEquals("chunky", chunkyToggle.toggleStateSource());
        assertEquals("off", chunkyToggle.toggleStartState());
    }

    @Test
    void serverSelectionIsSharedByCreateAndDuplicate() throws Exception {
        GuiConfig config = GuiConfigLoader.loadFromYaml(bundledYaml(), LOGGER);

        GuiSlotRangeDefinition servers = config.get("select-server").orElseThrow().slotRanges().get(0);
        assertEquals(0, servers.from(), "zwei volle Reihen, damit beim Duplizieren alle Server Platz haben");
        assertEquals(17, servers.to());
        GuiTrigger pickServer = servers.action().anyClickTrigger();
        assertEquals("nav server-selected %server-purpose% %selection:server%", pickServer.params().get("confirm-command"));
        for (int slot : new int[] {5, 7}) {
            GuiTrigger worldType = config.get("create-world").orElseThrow().rowSlots().get(slot).action().anyClickTrigger();
            assertEquals("select-server", worldType.guiId());
            assertEquals("create", worldType.params().get("server-purpose"));
        }
    }

    @Test
    void theShippedGuisYamlIsNotListedAsSupersededButItsPredecessorIs() throws Exception {
        byte[] shipped;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("guis.yml")) {
            shipped = in.readAllBytes();
        }
        assertFalse(GuiConfigLoader.SUPERSEDED_DEFAULT_HASHES.contains(GuiConfigLoader.sha256(shipped)),
            "sonst würde die aktuelle guis.yml bei jedem Start erneut ersetzt");
        assertTrue(GuiConfigLoader.SUPERSEDED_DEFAULT_HASHES.contains(
            "c96d88033aabc0834c94a34c4a7c8bc3e31271084d60a0f5e95c60b183b09387"), "Stand vom 23.10.2026 auf den Servern");
        assertTrue(GuiConfigLoader.SUPERSEDED_DEFAULT_HASHES.contains(
            "a6c4a0110bd317371ad9c4e9b4f178456675f04b3dc2f41a8840cc2412816b81"), "Stand vom 30.10.2026 (Sortier-Item schloss das Menü)");
        assertTrue(GuiConfigLoader.SUPERSEDED_DEFAULT_HASHES.contains(
            "3df5cacfac74668c47c384811c029705e49df2181d8902b222e85cfd3be5ec05"), "Stand vom 30.10.2026 (Serverauswahl mit nur 6 Plätzen)");
        assertTrue(GuiConfigLoader.SUPERSEDED_DEFAULT_HASHES.contains(
            "7390369f463412c4d63fbbf0d186e64a3b7434a75bb68bbc923792e7bd7b8d5f"), "Stand vom 30.10.2026 (ohne Instanz-Auswahl für Template-Welten)");
    }

    @Test
    void templateWorldsOfferAnInstanceSelection() throws Exception {
        GuiConfig config = GuiConfigLoader.loadFromYaml(bundledYaml(), LOGGER);

        GuiSlotRangeDefinition instances = config.get("select-instance").orElseThrow().slotRanges().get(0);
        assertEquals("world-instances", instances.source());
        assertEquals("nav join-instance %instance_id%", instances.action().anyClickTrigger().command());
    }

    private YamlConfiguration bundledYaml() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("guis.yml")) {
            assertNotNull(in, "guis.yml muss auf dem Klassenpfad liegen");
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return yaml;
    }

    @Test
    void toggleWithoutStateSourceKeepsSessionBehaviour() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("guis.yml")) {
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        GuiTrigger permissionToggle = GuiConfigLoader.loadFromYaml(yaml, LOGGER)
            .get("edit-friend").orElseThrow().rowSlots().get(3).action().anyClickTrigger();
        assertNull(permissionToggle.toggleStateSource());
    }

    @Test
    void paginationSlotThatCollidesWithRowSlotIsRejected() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(String.join("\n",
            "guis:",
            "  test:",
            "    gui-size: 54",
            "    position: bottom",
            "    slots:",
            "      slot-1:",
            "        material: \"STONE\"",
            "    pagination:",
            "      previous:",
            "        slot: 1",
            "      next:",
            "        slot: 9",
            ""
        ));

        assertThrows(GuiConfigException.class, () -> GuiConfigLoader.loadFromYaml(yaml, LOGGER));
    }
}
