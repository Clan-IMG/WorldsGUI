package net.clanimg.worldsGUI.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.clanimg.worldsGUI.model.WorldEntry;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

class GameModeRulesTest {
    private static final String OWNER = "8e167ad3-ad36-45a8-ba56-6a590f79164b";

    private static WorldEntry world(Map<String, String> modes, String sourceType) {
        return new WorldEntry("WrobelXXL-1", OWNER, "WrobelXXL", null, null, sourceType, "build", null, List.of(),
            List.of("SkyCryGaming", "Ben"), List.of(), "Burg", "STONE", true, false, null, null, null, null, null, null, modes);
    }

    @Test
    void ownSettingWinsOverEveryoneElse() {
        WorldEntry entry = world(Map.of("*", "SURVIVAL", "skycrygaming", "CREATIVE"), "private");

        assertEquals(Optional.of(GameMode.CREATIVE), GameModeRules.enforced(entry, "SkyCryGaming", "other-uuid"));
        assertEquals(Optional.of(GameMode.SURVIVAL), GameModeRules.enforced(entry, "Ben", "ben-uuid"), "eingeladen, ohne eigene Einstellung");
        assertEquals(Optional.of(GameMode.SURVIVAL), GameModeRules.enforced(entry, "Fremder", "x"), "nicht aufgelistet");
    }

    @Test
    void settingOfARemovedPlayerNoLongerApplies() {
        WorldEntry entry = world(Map.of("*", "SPECTATOR", "alterfreund", "CREATIVE"), "private");

        assertEquals(Optional.of(GameMode.SPECTATOR), GameModeRules.enforced(entry, "AlterFreund", "x"));
    }

    @Test
    void ownerTicketWorldsAndUnsetWorldsAreNotEnforced() {
        assertEquals(Optional.empty(), GameModeRules.enforced(world(Map.of("*", "SURVIVAL"), "private"), "WrobelXXL", OWNER));
        assertEquals(Optional.empty(), GameModeRules.enforced(world(Map.of("*", "SURVIVAL"), "ticket"), "Ben", "x"));
        assertEquals(Optional.empty(), GameModeRules.enforced(world(Map.of(), "private"), "Ben", "x"));
    }
}
