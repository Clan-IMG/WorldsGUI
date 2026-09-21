package net.clanimg.worldsGUI.guiconfig;

import java.util.List;
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
    private final List<GuiSlotRangeDefinition> slotRanges;
    private final String autoContentSource;
    private final String returnGuiId;

    public GuiDefinition(
        String id,
        int size,
        String title,
        GuiPosition position,
        Map<Integer, GuiSlotDefinition> rowSlots,
        List<GuiSlotRangeDefinition> slotRanges,
        String autoContentSource,
        String returnGuiId
    ) {
        this.id = id;
        this.size = size;
        this.title = title;
        this.position = position;
        this.rowSlots = rowSlots == null ? Map.of() : Map.copyOf(rowSlots);
        this.slotRanges = slotRanges == null ? List.of() : List.copyOf(slotRanges);
        this.autoContentSource = autoContentSource;
        this.returnGuiId = returnGuiId;
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

    /**
     * Alle "slot-range"-Blöcke dieses GUIs in der Reihenfolge der Datei. Neben "slot-range" sind
     * weitere Bereiche mit Suffix erlaubt (z.B. "slot-range-manual"), damit ein GUI mehrere
     * dynamische Listen (z.B. Auto- und manuelle Backups) darstellen kann.
     */
    public List<GuiSlotRangeDefinition> slotRanges() {
        return slotRanges;
    }

    /**
     * Für GUIs wie "my-worlds"/"invited-worlds": Name der impliziten Datenquelle
     * (z.B. "own-worlds", "invited-worlds"), die automatisch die freien Slots außerhalb
     * der Zeilen-Notation befüllt. Null, wenn kein Auto-Content vorgesehen ist.
     */
    public String autoContentSource() {
        return autoContentSource;
    }

    /**
     * Optionales, festes Ziel-GUI für den "return"-Trigger (Feld "return-gui-id" in guis.yml).
     * Überschreibt die history-basierte Navigation, z.B. wenn dieses GUI (wie select-friend)
     * durch ein dialog-input immer wieder sich selbst in die Historie schiebt.
     */
    public String returnGuiId() {
        return returnGuiId;
    }
}
