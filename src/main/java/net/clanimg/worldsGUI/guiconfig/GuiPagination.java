package net.clanimg.worldsGUI.guiconfig;

import java.util.List;

/**
 * Blätter-Knöpfe für GUIs mit automatisch befüllter Welten-Liste ("pagination:" in guis.yml).
 * Die Knöpfe erscheinen nur, wenn es eine vorherige bzw. nächste Seite gibt; sonst bleibt der Slot leer.
 *
 * @param previousSlot Zeilen-Slot (1-9) des "Vorherige Seite"-Knopfs
 * @param nextSlot     Zeilen-Slot (1-9) des "Nächste Seite"-Knopfs
 */
public record GuiPagination(
    int previousSlot,
    String previousMaterial,
    String previousTitle,
    List<String> previousLore,
    int nextSlot,
    String nextMaterial,
    String nextTitle,
    List<String> nextLore
) {
    public GuiPagination {
        previousLore = previousLore == null ? List.of() : List.copyOf(previousLore);
        nextLore = nextLore == null ? List.of() : List.copyOf(nextLore);
    }
}
