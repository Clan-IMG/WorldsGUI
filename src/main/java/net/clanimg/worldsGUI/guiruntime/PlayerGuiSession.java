package net.clanimg.worldsGUI.guiruntime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Pro-Spieler-Zustand für die guis.yml Runtime-Engine: Auswahl-Werte (select),
 * Übergabewerte (anvil-input params), generische open-gui Params, Navigations-Historie
 * (für "return") sowie Mehrfachklick-Zähler (für "clicks" bei command-Triggern).
 */
public final class PlayerGuiSession {
    private final Map<String, String> selections = new LinkedHashMap<>();
    private final Map<String, String> params = new LinkedHashMap<>();
    private final Map<String, String> guiParams = new LinkedHashMap<>();
    private final Map<String, Integer> clickCounters = new LinkedHashMap<>();
    private final Deque<String> history = new ArrayDeque<>();
    private String currentGuiId;
    private String currentWorld;

    public void putSelection(String selectId, String value) {
        if (selectId != null && !selectId.isBlank()) {
            selections.put(selectId, value);
        }
    }

    public String selection(String selectId) {
        return selectId == null ? null : selections.get(selectId);
    }

    public void putParam(String key, String value) {
        if (key != null && !key.isBlank()) {
            params.put(key, value);
        }
    }

    public void clearParam(String key) {
        if (key != null && !key.isBlank()) {
            params.remove(key);
        }
    }

    public String param(String key) {
        return key == null ? null : params.get(key);
    }

    /** Speichert die (bereits expandierten) "params" eines open-gui Triggers unter ihrem Original-Schlüssel. */
    public void putGuiParams(Map<String, String> resolvedParams) {
        if (resolvedParams != null) {
            guiParams.putAll(resolvedParams);
        }
    }

    public String guiParam(String key) {
        return key == null ? null : guiParams.get(key);
    }

    public Set<String> guiParamKeys() {
        return Set.copyOf(guiParams.keySet());
    }

    public String currentWorld() {
        return currentWorld;
    }

    public void setCurrentWorld(String worldName) {
        this.currentWorld = worldName;
    }

    public String currentGuiId() {
        return currentGuiId;
    }

    public void setCurrentGuiId(String guiId) {
        this.currentGuiId = guiId;
        clickCounters.clear();
    }

    public void pushCurrentToHistory() {
        if (currentGuiId != null) {
            history.push(currentGuiId);
        }
    }

    public String popHistory() {
        return history.poll();
    }

    public void clearHistory() {
        history.clear();
    }

    public int incrementClickCounter(String key) {
        return clickCounters.merge(key, 1, Integer::sum);
    }

    public void resetClickCounter(String key) {
        clickCounters.remove(key);
    }

    public void reset() {
        selections.clear();
        params.clear();
        guiParams.clear();
        clickCounters.clear();
        history.clear();
        currentGuiId = null;
        currentWorld = null;
    }
}
