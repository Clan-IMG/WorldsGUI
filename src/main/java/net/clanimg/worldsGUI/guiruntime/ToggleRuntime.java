package net.clanimg.worldsGUI.guiruntime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import net.clanimg.worldsGUI.guiconfig.GuiToggleState;
import net.clanimg.worldsGUI.guiconfig.GuiTrigger;
import org.bukkit.entity.Player;

/**
 * Hilfsfunktionen für toggle-Trigger (State-Auflösung und Scope-Key).
 */
public final class ToggleRuntime {
    /** Live-Quellen für "state-source" (Name -> (Spieler, Welt) -> State-ID oder null). */
    private static final Map<String, BiFunction<Player, String, String>> STATE_SOURCES = new ConcurrentHashMap<>();

    private ToggleRuntime() {
    }

    public static void registerStateSource(String name, BiFunction<Player, String, String> resolver) {
        STATE_SOURCES.put(name.toLowerCase(java.util.Locale.ROOT), resolver);
    }

    public static String scopedToggleId(GuiTrigger trigger, Player player, PlayerGuiSession session, Map<String, String> extra) {
        String base = trigger.toggleId() == null ? "" : trigger.toggleId().trim();
        if (base.isBlank()) {
            return "";
        }

        String world = normalize(PlaceholderExpander.expand("%world%", player, session, extra));
        String target = normalize(PlaceholderExpander.expand("%target_player_name%", player, session, extra));
        if (world.isBlank() && target.isBlank()) {
            return base;
        }
        return base + "|" + world + "|" + target;
    }

    public static String currentStateId(GuiTrigger trigger, Player player, PlayerGuiSession session, Map<String, String> extra) {
        String source = trigger.toggleStateSource();
        if (source != null) {
            BiFunction<Player, String, String> resolver = STATE_SOURCES.get(source.toLowerCase(java.util.Locale.ROOT));
            String world = normalize(PlaceholderExpander.expand("%world%", player, session, extra));
            String live = resolver == null || world.isBlank() || "%world%".equals(world) ? null : resolver.apply(player, world);
            if (live != null && trigger.toggleStates().containsKey(live)) {
                return live;
            }
        }

        String scopedId = scopedToggleId(trigger, player, session, extra);

        if (session != null && !scopedId.isBlank()) {
            String scoped = session.selection(scopedId);
            if (scoped != null && !scoped.isBlank() && trigger.toggleStates().containsKey(scoped)) {
                return scoped;
            }
        }

        String start = trigger.toggleStartState();
        if (start != null && trigger.toggleStates().containsKey(start)) {
            return start;
        }

        return trigger.toggleStates().keySet().stream().findFirst().orElse("");
    }

    public static GuiToggleState currentState(GuiTrigger trigger, Player player, PlayerGuiSession session, Map<String, String> extra) {
        String id = currentStateId(trigger, player, session, extra);
        return trigger.toggleStates().get(id);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}