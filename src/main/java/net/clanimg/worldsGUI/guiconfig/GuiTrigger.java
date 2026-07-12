package net.clanimg.worldsGUI.guiconfig;

import java.util.Map;

/**
 * Ein einzelner Trigger aus einem "action"-Block in guis.yml.
 * Je nach {@link TriggerType} sind nur bestimmte Felder belegt, alle anderen bleiben leer/neutral.
 */
public final class GuiTrigger {
    private final TriggerType type;
    private final String command;
    private final int clicks;
    private final boolean chatFeedback;
    private final String guiId;
    private final Map<String, String> params;
    private final String selectId;
    private final String selectValue;
    private final String anvilTitle;
    private final String paramKey;
    private final String toggleId;
    private final String toggleStartState;
    private final Map<String, GuiToggleState> toggleStates;

    private GuiTrigger(
        TriggerType type,
        String command,
        int clicks,
        boolean chatFeedback,
        String guiId,
        Map<String, String> params,
        String selectId,
        String selectValue,
        String anvilTitle,
        String paramKey,
        String toggleId,
        String toggleStartState,
        Map<String, GuiToggleState> toggleStates
    ) {
        this.type = type;
        this.command = command;
        this.clicks = clicks;
        this.chatFeedback = chatFeedback;
        this.guiId = guiId;
        this.params = params == null ? Map.of() : Map.copyOf(params);
        this.selectId = selectId;
        this.selectValue = selectValue;
        this.anvilTitle = anvilTitle;
        this.paramKey = paramKey;
        this.toggleId = toggleId;
        this.toggleStartState = toggleStartState;
        this.toggleStates = toggleStates == null ? Map.of() : Map.copyOf(toggleStates);
    }

    public static GuiTrigger command(String command, int clicks, boolean chatFeedback) {
        return new GuiTrigger(TriggerType.COMMAND, command, Math.max(1, clicks), chatFeedback, null, null, null, null, null, null, null, null, null);
    }

    public static GuiTrigger openGui(String guiId, Map<String, String> params) {
        return new GuiTrigger(TriggerType.OPEN_GUI, null, 1, true, guiId, params, null, null, null, null, null, null, null);
    }

    public static GuiTrigger returnTrigger() {
        return new GuiTrigger(TriggerType.RETURN, null, 1, true, null, null, null, null, null, null, null, null, null);
    }

    public static GuiTrigger select(String selectId, String selectValue, String guiId, Map<String, String> params) {
        return new GuiTrigger(TriggerType.SELECT, null, 1, true, guiId, params, selectId, selectValue, null, null, null, null, null);
    }

    public static GuiTrigger anvilInput(String anvilTitle, String guiId, String paramKey) {
        return new GuiTrigger(TriggerType.ANVIL_INPUT, null, 1, true, guiId, null, null, null, anvilTitle, paramKey, null, null, null);
    }

    public static GuiTrigger toggle(String toggleId, String startState, Map<String, GuiToggleState> states) {
        return new GuiTrigger(TriggerType.TOGGLE, null, 1, true, null, null, null, null, null, null, toggleId, startState, states);
    }

    public TriggerType type() {
        return type;
    }

    public String command() {
        return command;
    }

    public int clicks() {
        return clicks;
    }

    public boolean chatFeedback() {
        return chatFeedback;
    }

    public String guiId() {
        return guiId;
    }

    public Map<String, String> params() {
        return params;
    }

    public String selectId() {
        return selectId;
    }

    public String selectValue() {
        return selectValue;
    }

    public String anvilTitle() {
        return anvilTitle;
    }

    public String paramKey() {
        return paramKey;
    }

    public String toggleId() {
        return toggleId;
    }

    public String toggleStartState() {
        return toggleStartState;
    }

    public Map<String, GuiToggleState> toggleStates() {
        return toggleStates;
    }
}
