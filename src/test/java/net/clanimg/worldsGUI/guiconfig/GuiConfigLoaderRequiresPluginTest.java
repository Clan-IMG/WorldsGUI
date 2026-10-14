package net.clanimg.worldsGUI.guiconfig;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuiConfigLoaderRequiresPluginTest {
    private static final Logger LOGGER = Logger.getLogger("GuiConfigLoaderRequiresPluginTest");

    @Test
    void parsesRequiresPluginOnRowSlotsAndRanges() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(String.join("\n",
            "guis:",
            "  test:",
            "    gui-size: 27",
            "    position: middle",
            "    slots:",
            "      slot-1:",
            "        material: \"STONE\"",
            "        requires-plugin: \"MapMaker\"",
            "      slot-2:",
            "        material: \"DIRT\"",
            "      slot-3:",
            "        material: \"DIRT\"",
            "        requires-plugin: \"  \"",
            "      slot-range:",
            "        from: 0",
            "        to: 8",
            "        source: \"servers\"",
            "        requires-plugin: \"Nexo\"",
            ""
        ));

        GuiConfig config = GuiConfigLoader.loadFromYaml(yaml, LOGGER);
        GuiDefinition gui = config.get("test").orElseThrow();

        assertEquals("MapMaker", gui.rowSlots().get(1).requiresPlugin());
        assertNull(gui.rowSlots().get(2).requiresPlugin());
        assertNull(gui.rowSlots().get(3).requiresPlugin(), "leerer Wert wird wie nicht gesetzt behandelt");
        assertEquals("Nexo", gui.slotRanges().get(0).requiresPlugin());
    }

    @Test
    void bundledGuisYamlContainsMapMakerPages() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("guis.yml")) {
            assertNotNull(in, "guis.yml muss auf dem Klassenpfad liegen");
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        GuiConfig config = GuiConfigLoader.loadFromYaml(yaml, LOGGER);

        GuiSlotDefinition moreOptions = config.get("edit-world").orElseThrow().rowSlots().get(8);
        assertNotNull(moreOptions);
        assertEquals("MapMaker", moreOptions.requiresPlugin());
        assertEquals("edit-world-more", moreOptions.action().anyClickTrigger().guiId());

        GuiDefinition more = config.get("edit-world-more").orElseThrow();
        assertEquals(27, more.size());
        assertEquals("edit-world", more.returnGuiId());
        GuiSlotDefinition studio = more.rowSlots().get(5);
        assertEquals("MapMaker", studio.requiresPlugin());
        GuiTrigger trigger = studio.action().anyClickTrigger();
        assertEquals(TriggerType.COMMAND, trigger.type());
        assertEquals("mapmaker studio %world%", trigger.command());
        assertTrue(trigger.chatFeedback());
    }
}
