package net.clanimg.worldsGUI.guiruntime;

import java.util.LinkedHashMap;
import java.util.Map;
import net.clanimg.worldsGUI.guiconfig.GuiTrigger;
import org.bukkit.entity.Player;

/**
 * Führt einen aufgelösten {@link GuiTrigger} aus (Platzhalter-Expansion + Seiteneffekt).
 * Das eigentliche Rendern/Öffnen von Inventaren übernimmt {@link GuiOpener} (Bukkit-Schicht).
 */
public final class TriggerDispatcher {
    private final GuiOpener guiOpener;
    private final CommandDispatcher commandDispatcher;
    private final AnvilInputRequester anvilInputRequester;

    public TriggerDispatcher(GuiOpener guiOpener, CommandDispatcher commandDispatcher, AnvilInputRequester anvilInputRequester) {
        this.guiOpener = guiOpener;
        this.commandDispatcher = commandDispatcher;
        this.anvilInputRequester = anvilInputRequester;
    }

    /**
     * @param extra      Ad-hoc Platzhalter für diesen Klick (z.B. target_player_name, server_id, world).
     * @param triggerKey stabile Kennung für diesen Slot/Trigger (z.B. "gui-id:slot-key"),
     *                   wird nur für die "clicks"-Bestätigung bei command-Triggern benötigt.
     */
    public void execute(Player player, PlayerGuiSession session, GuiTrigger trigger, Map<String, String> extra, String triggerKey) {
        if (trigger == null) {
            return;
        }

        switch (trigger.type()) {
            case COMMAND -> executeCommand(player, session, trigger, extra, triggerKey);
            case OPEN_GUI -> executeOpenGui(player, session, trigger, extra);
            case RETURN -> executeReturn(player, session);
            case SELECT -> executeSelect(player, session, trigger, extra);
            case ANVIL_INPUT -> anvilInputRequester.request(player, session, trigger);
        }
    }

    private void executeCommand(Player player, PlayerGuiSession session, GuiTrigger trigger, Map<String, String> extra, String triggerKey) {
        if (trigger.clicks() > 1 && triggerKey != null) {
            int count = session.incrementClickCounter(triggerKey);
            if (count < trigger.clicks()) {
                player.sendMessage("§eNoch " + (trigger.clicks() - count) + "x klicken zum Bestätigen.");
                return;
            }
            session.resetClickCounter(triggerKey);
        }

        String expanded = PlaceholderExpander.expand(trigger.command(), player, session, extra);
        if (expanded == null || expanded.isBlank()) {
            player.sendMessage("§cDieser Command konnte nicht ausgeführt werden (fehlender Wert).");
            return;
        }

        player.closeInventory();
        commandDispatcher.dispatch(player, expanded);
    }

    private void executeOpenGui(Player player, PlayerGuiSession session, GuiTrigger trigger, Map<String, String> extra) {
        Map<String, String> expandedParams = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : trigger.params().entrySet()) {
            expandedParams.put(entry.getKey(), PlaceholderExpander.expand(entry.getValue(), player, session, extra));
        }
        session.putGuiParams(expandedParams);
        session.pushCurrentToHistory();
        session.setCurrentGuiId(trigger.guiId());
        guiOpener.openGui(player, trigger.guiId());
    }

    private void executeReturn(Player player, PlayerGuiSession session) {
        String previous = session.popHistory();
        if (previous == null) {
            player.closeInventory();
            return;
        }
        session.setCurrentGuiId(previous);
        guiOpener.openGui(player, previous);
    }

    private void executeSelect(Player player, PlayerGuiSession session, GuiTrigger trigger, Map<String, String> extra) {
        String value = PlaceholderExpander.expand(trigger.selectValue(), player, session, extra);
        session.putSelection(trigger.selectId(), value);

        Map<String, String> expandedParams = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : trigger.params().entrySet()) {
            expandedParams.put(entry.getKey(), PlaceholderExpander.expand(entry.getValue(), player, session, extra));
        }
        session.putGuiParams(expandedParams);

        if (trigger.guiId() != null && !trigger.guiId().isBlank()) {
            session.pushCurrentToHistory();
            session.setCurrentGuiId(trigger.guiId());
            guiOpener.openGui(player, trigger.guiId());
        }
    }
}
