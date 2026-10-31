package net.clanimg.worldsGUI.gui;

import java.util.Locale;
import java.util.Optional;
import net.clanimg.worldsGUI.model.WorldEntry;
import org.bukkit.GameMode;

/**
 * Welcher Spielmodus in einer privaten Welt für einen Spieler gilt: eigene Einstellung (nur eingeladene Spieler), sonst
 * die für "alle anderen" (Grundgestein im Menü). Der Besitzer ist ausgenommen; Admins/OPs prüft der Aufrufer.
 */
final class GameModeRules {
    /** Schlüssel für alle Spieler ohne eigene Einstellung. */
    static final String OTHERS = "*";

    private GameModeRules() {
    }

    static Optional<GameMode> enforced(WorldEntry entry, String playerName, String playerUuid) {
        if (entry == null || !"private".equalsIgnoreCase(entry.sourceType()) || entry.isArchived()) {
            return Optional.empty();
        }
        if (entry.ownerUuid() != null && entry.ownerUuid().equalsIgnoreCase(playerUuid)) {
            return Optional.empty();
        }
        String mode = null;
        boolean invited = entry.invitedPlayers().stream().anyMatch(name -> name.equalsIgnoreCase(playerName));
        if (invited) {
            mode = entry.gameModes().get(playerName.toLowerCase(Locale.ROOT));
        }
        if (mode == null) {
            mode = entry.gameModes().get(OTHERS);
        }
        return parse(mode);
    }

    static Optional<GameMode> parse(String mode) {
        if (mode == null || mode.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(GameMode.valueOf(mode.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
