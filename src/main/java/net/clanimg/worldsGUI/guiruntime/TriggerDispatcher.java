package net.clanimg.worldsGUI.guiruntime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import net.clanimg.worldsGUI.guiconfig.GuiTrigger;
import org.bukkit.entity.Player;

/**
 * Führt einen aufgelösten {@link GuiTrigger} aus (Platzhalter-Expansion + Seiteneffekt).
 * Das eigentliche Rendern/Öffnen von Inventaren übernimmt {@link GuiOpener} (Bukkit-Schicht).
 */
public final class TriggerDispatcher {
    @FunctionalInterface
    public interface RuntimeMessageSender {
        void send(Player player, String key, String fallbackLiteral, String... replacements);
    }

    private final GuiOpener guiOpener;
    private final CommandDispatcher commandDispatcher;
    private final AnvilInputRequester anvilInputRequester;
    private final RuntimeMessageSender messageSender;
    private final UnaryOperator<String> returnGuiIdResolver;

    public TriggerDispatcher(
        GuiOpener guiOpener,
        CommandDispatcher commandDispatcher,
        AnvilInputRequester anvilInputRequester,
        RuntimeMessageSender messageSender,
        UnaryOperator<String> returnGuiIdResolver
    ) {
        this.guiOpener = guiOpener;
        this.commandDispatcher = commandDispatcher;
        this.anvilInputRequester = anvilInputRequester;
        this.messageSender = messageSender;
        this.returnGuiIdResolver = returnGuiIdResolver;
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
            case TOGGLE -> executeToggle(player, session, trigger, extra);
        }
    }

    private void executeCommand(Player player, PlayerGuiSession session, GuiTrigger trigger, Map<String, String> extra, String triggerKey) {
        if (trigger.clicks() > 1 && triggerKey != null) {
            int count = session.incrementClickCounter(triggerKey);
            if (count < trigger.clicks()) {
                messageSender.send(
                    player,
                    "runtime.confirm-clicks-remaining",
                    "&eNoch %remaining%x klicken zum Bestätigen.",
                    "%remaining%",
                    String.valueOf(trigger.clicks() - count)
                );
                return;
            }
            session.resetClickCounter(triggerKey);
        }

        String expanded = PlaceholderExpander.expand(trigger.command(), player, session, extra);
        if (expanded == null || expanded.isBlank()) {
            messageSender.send(player, "runtime.command-missing-value", "&cDieser Command konnte nicht ausgeführt werden (fehlender Wert).");
            return;
        }

        boolean navigatesAfterCommand = trigger.guiId() != null && !trigger.guiId().isBlank();
        if (!navigatesAfterCommand && trigger.chatFeedback()) {
            player.closeInventory();
        }
        commandDispatcher.dispatch(player, expanded, trigger.chatFeedback());

        if (navigatesAfterCommand) {
            session.pushCurrentToHistory();
            session.setCurrentGuiId(trigger.guiId());
            guiOpener.openGui(player, trigger.guiId());
        }
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
        if ("select-friend".equalsIgnoreCase(session.currentGuiId())) {
            session.clearParam("search");
        }

        // Festes Ziel-GUI (return-gui-id in guis.yml) hat Vorrang vor der Historie, damit
        // GUIs, die sich selbst wiederholt in die Historie schieben (z.B. Suche/Filter), immer
        // an einem sinnvollen Punkt landen.
        String fixedTarget = returnGuiIdResolver == null ? null : returnGuiIdResolver.apply(session.currentGuiId());
        if (fixedTarget != null && !fixedTarget.isBlank()) {
            session.setCurrentGuiId(fixedTarget);
            guiOpener.openGui(player, fixedTarget);
            return;
        }

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

    private void executeToggle(Player player, PlayerGuiSession session, GuiTrigger trigger, Map<String, String> extra) {
        if (trigger.toggleStates().isEmpty()) {
            return;
        }

        String currentStateId = ToggleRuntime.currentStateId(trigger, player, session, extra);
        var state = trigger.toggleStates().get(currentStateId);
        if (state == null) {
            return;
        }

        String scopedId = ToggleRuntime.scopedToggleId(trigger, player, session, extra);
        if (!scopedId.isBlank()) {
            session.putSelection(scopedId, currentStateId);
        }
        if (trigger.toggleId() != null && !trigger.toggleId().isBlank()) {
            session.putSelection(trigger.toggleId(), currentStateId);
        }

        String expanded = PlaceholderExpander.expand(state.command(), player, session, extra);
        if (expanded == null || expanded.isBlank()) {
            messageSender.send(player, "runtime.toggle-command-missing-value", "&cDieser Toggle-Command konnte nicht ausgeführt werden (fehlender Wert).");
            return;
        }

        commandDispatcher.dispatch(player, expanded, state.chatFeedback());

        String next = state.next();
        if (next == null || next.isBlank() || !trigger.toggleStates().containsKey(next)) {
            next = trigger.toggleStartState();
        }
        if (next == null || next.isBlank() || !trigger.toggleStates().containsKey(next)) {
            next = currentStateId;
        }

        if (!scopedId.isBlank()) {
            session.putSelection(scopedId, next);
        }
        if (trigger.toggleId() != null && !trigger.toggleId().isBlank()) {
            session.putSelection(trigger.toggleId(), next);
        }

        String guiId = session.currentGuiId();
        if (guiId != null && !guiId.isBlank()) {
            guiOpener.openGui(player, guiId);
        }
    }
}
