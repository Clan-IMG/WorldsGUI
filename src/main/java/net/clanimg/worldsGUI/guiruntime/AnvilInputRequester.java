package net.clanimg.worldsGUI.guiruntime;

import net.clanimg.worldsGUI.guiconfig.GuiTrigger;
import org.bukkit.entity.Player;

/**
 * Fordert eine Texteingabe für einen anvil-input Trigger an. Die Implementierung entscheidet,
 * ob die Minecraft Dialog-API (1.21.6+) oder ein Chat-Fallback genutzt wird.
 */
public interface AnvilInputRequester {
    void request(Player player, PlayerGuiSession session, GuiTrigger trigger);
}
