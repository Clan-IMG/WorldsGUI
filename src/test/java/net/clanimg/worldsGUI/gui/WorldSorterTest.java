package net.clanimg.worldsGUI.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import net.clanimg.worldsGUI.model.WorldEntry;
import org.junit.jupiter.api.Test;

class WorldSorterTest {
    // API-Reihenfolge: neueste Erstellung zuerst
    private final WorldEntry newest = world("WrobelXXL-3003", "&6Zebra Turm");
    private final WorldEntry middle = world("WrobelXXL-2002", "alpha");
    private final WorldEntry oldest = world("WrobelXXL-1001", null);
    private final List<WorldEntry> apiOrder = List.of(newest, middle, oldest);

    @Test
    void creationOrderComesFromTheApiInBothDirections() {
        assertEquals(List.of(newest, middle, oldest), WorldSorter.sort(apiOrder, WorldSortMode.CREATED_NEWEST, Map.of()));
        assertEquals(List.of(oldest, middle, newest), WorldSorter.sort(apiOrder, WorldSortMode.CREATED_OLDEST, Map.of()));
    }

    @Test
    void lastVisitSortsBothWaysAndNeverVisitedWorldsStayAtTheEnd() {
        Map<String, Long> visits = Map.of("wrobelxxl-1001", 2_000L, "wrobelxxl-3003", 1_000L);

        assertEquals(List.of(oldest, newest, middle), WorldSorter.sort(apiOrder, WorldSortMode.VISITED_NEWEST, visits));
        assertEquals(List.of(newest, oldest, middle), WorldSorter.sort(apiOrder, WorldSortMode.VISITED_OLDEST, visits));
    }

    @Test
    void namesSortByDisplayNameWithoutColorCodesAndFallBackToTheWorldName() {
        // "alpha" < "WrobelXXL-1001" (Weltname, kein Anzeigename) < "Zebra Turm" (ohne &6)
        assertEquals(List.of(middle, oldest, newest), WorldSorter.sort(apiOrder, WorldSortMode.NAME_ASC, Map.of()));
        assertEquals(List.of(newest, oldest, middle), WorldSorter.sort(apiOrder, WorldSortMode.NAME_DESC, Map.of()));
    }

    @Test
    void sortModesCycleInBothDirectionsAndParseTheirKeys() {
        assertEquals(WorldSortMode.CREATED_OLDEST, WorldSortMode.CREATED_NEWEST.next());
        assertEquals(WorldSortMode.CREATED_NEWEST, WorldSortMode.NAME_DESC.next());
        assertEquals(WorldSortMode.NAME_DESC, WorldSortMode.CREATED_NEWEST.previous());
        for (WorldSortMode mode : WorldSortMode.values()) {
            assertEquals(mode, WorldSortMode.parse(mode.key()).orElseThrow());
        }
    }

    private static WorldEntry world(String worldName, String displayName) {
        return new WorldEntry(worldName, "8e167ad3-ad36-45a8-ba56-6a590f79164b", "WrobelXXL", null, null, "private", "build",
            null, List.of(), List.of(), List.of(), displayName, "GRASS_BLOCK", false, false, null, null, null, null, null, null);
    }
}
