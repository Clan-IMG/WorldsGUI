package net.clanimg.worldsGUI.guiconfig;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Container für alle aus guis.yml geladenen GUI-Definitionen.
 */
public final class GuiConfig {
    private final Map<String, GuiDefinition> guis;

    public GuiConfig(Map<String, GuiDefinition> guis) {
        this.guis = guis == null ? Map.of() : Map.copyOf(guis);
    }

    public Optional<GuiDefinition> get(String guiId) {
        if (guiId == null || guiId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(guis.get(guiId.trim().toLowerCase(Locale.ROOT)));
    }

    public Map<String, GuiDefinition> all() {
        return guis;
    }

    public boolean isEmpty() {
        return guis.isEmpty();
    }
}
