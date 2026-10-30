package net.clanimg.worldsGUI.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import net.clanimg.worldsGUI.model.WorldEntry;

/**
 * Sortiert die eigenen Welten für "Meine Welten". Die API liefert die Welten bereits nach Erstellung sortiert
 * (neueste zuerst, {@code ORDER BY created_at DESC}); diese Reihenfolge ist die Basis und dient bei Gleichstand als
 * zweites Kriterium. Nie besuchte Welten stehen bei "Zuletzt besucht" in beiden Richtungen hinten.
 */
public final class WorldSorter {
    private static final Pattern COLOR_CODES = Pattern.compile("(?i)[&§][0-9A-FK-ORX]");

    private WorldSorter() {
    }

    /**
     * @param newestFirst Welten in API-Reihenfolge (neueste Erstellung zuerst)
     * @param lastVisits  letzter Besuch je Welt (Schlüssel in Kleinbuchstaben, Epoch-Millis)
     */
    public static List<WorldEntry> sort(List<WorldEntry> newestFirst, WorldSortMode mode, Map<String, Long> lastVisits) {
        Map<WorldEntry, Integer> creationRank = new IdentityHashMap<>();
        for (int i = 0; i < newestFirst.size(); i++) {
            creationRank.put(newestFirst.get(i), i);
        }
        Comparator<WorldEntry> byCreationNewest = Comparator.comparingInt(creationRank::get);
        Comparator<WorldEntry> comparator = switch (mode) {
            case CREATED_NEWEST -> byCreationNewest;
            case CREATED_OLDEST -> byCreationNewest.reversed();
            case VISITED_NEWEST -> byVisit(lastVisits, true).thenComparing(byCreationNewest);
            case VISITED_OLDEST -> byVisit(lastVisits, false).thenComparing(byCreationNewest);
            case NAME_ASC -> byName().thenComparing(byCreationNewest);
            case NAME_DESC -> byName().reversed().thenComparing(byCreationNewest);
        };
        List<WorldEntry> sorted = new ArrayList<>(newestFirst);
        sorted.sort(comparator);
        return sorted;
    }

    private static Comparator<WorldEntry> byVisit(Map<String, Long> lastVisits, boolean newestFirst) {
        return (a, b) -> {
            Long visitA = lastVisits.get(a.worldName().toLowerCase(Locale.ROOT));
            Long visitB = lastVisits.get(b.worldName().toLowerCase(Locale.ROOT));
            if (visitA == null || visitB == null) {
                // nie besucht -> ans Ende, unabhängig von der Richtung
                return visitA == null ? (visitB == null ? 0 : 1) : -1;
            }
            return newestFirst ? Long.compare(visitB, visitA) : Long.compare(visitA, visitB);
        };
    }

    private static Comparator<WorldEntry> byName() {
        return Comparator.comparing(WorldSorter::sortName, String.CASE_INSENSITIVE_ORDER);
    }

    static String sortName(WorldEntry entry) {
        String name = entry.displayName() == null || entry.displayName().isBlank() ? entry.worldName() : entry.displayName();
        return COLOR_CODES.matcher(name).replaceAll("").trim();
    }
}
