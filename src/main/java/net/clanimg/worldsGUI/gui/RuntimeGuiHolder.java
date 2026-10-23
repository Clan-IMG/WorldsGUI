package net.clanimg.worldsGUI.gui;

import java.util.HashMap;
import java.util.Map;
import net.clanimg.worldsGUI.guiconfig.GuiAction;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * InventoryHolder für über guis.yml gerenderte GUIs (neue Runtime-Engine).
 * Speichert pro absolutem Slot den zugehörigen {@link GuiAction} sowie Ad-hoc-Platzhalter
 * (z.B. server_id/server_name aus einer slot-range), damit ein Klick später aufgelöst werden kann.
 */
public final class RuntimeGuiHolder implements InventoryHolder {
    public record ClickHandler(GuiAction action, Map<String, String> extra) {
    }

    private final String guiId;
    private final Map<Integer, ClickHandler> clickHandlers = new HashMap<>();
    private final Map<Integer, Integer> pageButtons = new HashMap<>();

    public RuntimeGuiHolder(String guiId) {
        this.guiId = guiId;
    }

    public String guiId() {
        return guiId;
    }

    public void putClickHandler(int slot, GuiAction action, Map<String, String> extra) {
        clickHandlers.put(slot, new ClickHandler(action, extra));
    }

    public ClickHandler clickHandler(int slot) {
        return clickHandlers.get(slot);
    }

    /** Blätter-Knopf: Klick auf diesen Slot wechselt auf die Seite {@code targetPage}. */
    public void putPageButton(int slot, int targetPage) {
        pageButtons.put(slot, targetPage);
    }

    /** Ziel-Seite des Blätter-Knopfs in diesem Slot, oder null. */
    public Integer pageButton(int slot) {
        return pageButtons.get(slot);
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
