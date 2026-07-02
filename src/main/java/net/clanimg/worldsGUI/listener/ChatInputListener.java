package net.clanimg.worldsGUI.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.clanimg.worldsGUI.gui.GuiManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class ChatInputListener implements Listener {
    private final GuiManager guiManager;

    public ChatInputListener(GuiManager guiManager) {
        this.guiManager = guiManager;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        guiManager.handleChat(event);
    }
}
