package net.clanimg.worldsGUI.guiruntime;

import org.bukkit.entity.Player;

/** Führt einen bereits platzhalter-expandierten Command aus (z.B. via Bukkit.dispatchCommand). */
public interface CommandDispatcher {
    void dispatch(Player player, String command, boolean chatFeedback);
}
