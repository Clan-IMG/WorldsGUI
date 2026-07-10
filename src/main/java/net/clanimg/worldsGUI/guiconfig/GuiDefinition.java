package net.clanimg.worldsGUI.guiconfig;

import java.util.Map;

/**
 * Ein vollständiges GUI aus dem "guis:"-Block in guis.yml (ein Eintrag pro gui-id).
 */
public final class GuiDefinition {
    private final String id;
    private final int size;
    private final String title;
    private final GuiPosition position;
    private final Map<Integer, GuiSlotDefinition> rowSlots;
    private final GuiSlotRangeDefinition slotRange;
    private final String autoContentSource;

    public GuiDefinition(
        String id,
        int size,
        String title,
        GuiPosition position,
        Map<Integer, GuiSlotDefinition> rowSlots,
        GuiSlotRangeDefinition slotRange,
        String autoContentSource
    ) {
        this.id = id;
        this.size = size;
        this.title = title;
        this.position = position;
        this.rowSlots = rowSlots == null ? Map.of() : Map.copyOf(rowSlots);
        this.slotRange = slotRange;
        this.autoContentSource = autoContentSource;
    }

    public String id() {
        return id;
    }

    public int size() {
        return size;
    }

    public String title() {
        return title;
    }

    public GuiPosition position() {
        return position;
    }

    /** Zeilen-Notation-Slots, Schlüssel 1..9 (nicht der absolute Inventar-Index). */
    public Map<Integer, GuiSlotDefinition> rowSlots() {
        return rowSlots;
    }

    public GuiSlotRangeDefinition slotRange() {
        return slotRange;
    }

    /**
     * Für GUIs wie "my-worlds"/"invited-worlds": Name der impliziten Datenquelle
     * (z.B. "own-worlds", "invited-worlds"), die automatisch die freien Slots außerhalb
     * der Zeilen-Notation befüllt. Null, wenn kein Auto-Content vorgesehen ist.
     */
    public String autoContentSource() {
        return autoContentSource;
    }
}
