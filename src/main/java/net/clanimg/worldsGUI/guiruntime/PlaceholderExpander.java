package net.clanimg.worldsGUI.guiruntime;

import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.entity.Player;

/**
 * Löst alle in guis.yml dokumentierten Platzhalter in Titel-/Command-Templates auf.
 * Reihenfolge: eingebaute Platzhalter (player_name, world/world_id, ad-hoc "extra"-Werte
 * wie target_player_name/server_id/server_name) sowie "selection:" und "param:" haben
 * Vorrang vor generischen open-gui "params" (%name%, z.B. %confirm-command%), damit
 * GUI-Autoren keine sicherheitsrelevanten Platzhalter versehentlich überschreiben können.
 */
public final class PlaceholderExpander {
    private static final Pattern SELECTION_PATTERN = Pattern.compile("%selection:([a-zA-Z0-9_-]+)%");
    private static final Pattern PARAM_PATTERN = Pattern.compile("%param:([a-zA-Z0-9_-]+)%");

    private PlaceholderExpander() {
    }

    /**
     * @param extra Ad-hoc Platzhalter für den aktuellen Klick (z.B. target_player_name, server_id,
     *              server_name, world) - "world" hat Vorrang vor dem in der Session gespeicherten currentWorld.
     */
    public static String expand(String template, Player player, PlayerGuiSession session, Map<String, String> extra) {
        if (template == null || template.isEmpty()) {
            return template;
        }

        String result = template;

        if (player != null) {
            result = result.replace("%player_name%", player.getName());
        }

        String world = extra != null && extra.get("world") != null
            ? extra.get("world")
            : (session == null ? null : session.currentWorld());
        if (world != null) {
            result = result.replace("%world%", world);
            result = result.replace("%world_id%", world);
        }

        if (extra != null) {
            for (Map.Entry<String, String> entry : extra.entrySet()) {
                if ("world".equals(entry.getKey())) {
                    continue;
                }
                String placeholder = "%" + entry.getKey() + "%";
                if (entry.getValue() != null && result.contains(placeholder)) {
                    result = result.replace(placeholder, entry.getValue());
                }
            }
        }

        if (session != null) {
            result = replaceGroupPattern(result, SELECTION_PATTERN, session::selection);
            result = replaceGroupPattern(result, PARAM_PATTERN, session::param);
            result = replaceGuiParams(result, session);
        }

        return result;
    }

    private static String replaceGroupPattern(String input, Pattern pattern, Function<String, String> resolver) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder builder = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            String value = resolver.apply(matcher.group(1));
            builder.append(input, lastEnd, matcher.start());
            builder.append(value == null ? "" : value);
            lastEnd = matcher.end();
        }
        builder.append(input.substring(lastEnd));
        return builder.toString();
    }

    private static String replaceGuiParams(String input, PlayerGuiSession session) {
        String result = input;
        for (String key : session.guiParamKeys()) {
            String placeholder = "%" + key + "%";
            if (result.contains(placeholder)) {
                String value = session.guiParam(key);
                result = result.replace(placeholder, value == null ? "" : value);
            }
        }
        return result;
    }
}
