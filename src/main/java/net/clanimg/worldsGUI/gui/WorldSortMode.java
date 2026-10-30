package net.clanimg.worldsGUI.gui;

import java.util.Locale;
import java.util.Optional;

/** Sortierung der eigenen Welten in "Meine Welten" - wird per Klick auf das Sortier-Item durchgeschaltet. */
public enum WorldSortMode {
    CREATED_NEWEST("Erstellt: neueste zuerst"),
    CREATED_OLDEST("Erstellt: älteste zuerst"),
    VISITED_NEWEST("Zuletzt besucht: neueste zuerst"),
    VISITED_OLDEST("Zuletzt besucht: älteste zuerst"),
    NAME_ASC("Name: A → Z"),
    NAME_DESC("Name: Z → A");

    public static final WorldSortMode DEFAULT = CREATED_NEWEST;

    private final String label;

    WorldSortMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Platzhalter-Schlüssel, z. B. {@code created-newest}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public boolean usesVisits() {
        return this == VISITED_NEWEST || this == VISITED_OLDEST;
    }

    public WorldSortMode next() {
        WorldSortMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public WorldSortMode previous() {
        WorldSortMode[] values = values();
        return values[(ordinal() - 1 + values.length) % values.length];
    }

    public static Optional<WorldSortMode> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (WorldSortMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
