package net.clanimg.worldsGUI.guiconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class GuiConfigLoaderPaginationTest {
    private static final Logger LOGGER = Logger.getLogger("GuiConfigLoaderPaginationTest");

    @Test
    void bundledGuisYamlHasPaginationRecentWorldsAndChunkyToggle() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("guis.yml")) {
            assertNotNull(in, "guis.yml muss auf dem Klassenpfad liegen");
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        GuiConfig config = GuiConfigLoader.loadFromYaml(yaml, LOGGER);

        GuiDefinition myWorlds = config.get("my-worlds").orElseThrow();
        assertNotNull(myWorlds.pagination());
        assertEquals(1, myWorlds.pagination().previousSlot());
        assertEquals(8, myWorlds.pagination().nextSlot());
        assertEquals("recent-worlds", myWorlds.rowSlots().get(9).action().anyClickTrigger().guiId());

        GuiDefinition recent = config.get("recent-worlds").orElseThrow();
        assertEquals(18, recent.size());

        GuiTrigger chunkyToggle = config.get("edit-world").orElseThrow().rowSlots().get(6).action().anyClickTrigger();
        assertEquals(TriggerType.TOGGLE, chunkyToggle.type());
        assertEquals("chunky", chunkyToggle.toggleStateSource());
        assertEquals("off", chunkyToggle.toggleStartState());
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
