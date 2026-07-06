package net.clanimg.worldsGUI.listener;

import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerCommandEvent;

public final class ConsoleLoginListener implements Listener {
    private UUID actingPlayerId;

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        CommandSender sender = event.getSender();
        if (!(sender instanceof ConsoleCommandSender)) {
            return;
        }

        String raw = event.getCommand() == null ? "" : event.getCommand().trim();
        if (raw.isEmpty()) {
            return;
        }

        String lower = raw.toLowerCase(Locale.ROOT);

        if (lower.equals("exit")) {
            event.setCancelled(true);
            if (actingPlayerId == null) {
                sender.sendMessage("§eKein aktiver join-Modus.");
                return;
            }
            sender.sendMessage("§ajoin-Modus beendet. Du bist wieder normale Konsole.");
            actingPlayerId = null;
            return;
        }

        if (lower.startsWith("join ") || lower.startsWith("login ")) {
            event.setCancelled(true);
            String targetName;
            if (lower.startsWith("join ")) {
                targetName = raw.substring("join ".length()).trim();
            } else {
                targetName = raw.substring("login ".length()).trim();
            }
            if (targetName.isEmpty()) {
                sender.sendMessage("§cUsage: join <player>");
                return;
            }

            Player target = Bukkit.getPlayerExact(targetName);
            if (target == null || !target.isOnline()) {
                sender.sendMessage("§cSpieler nicht online: " + targetName);
                return;
            }

            actingPlayerId = target.getUniqueId();
            sender.sendMessage("§aKonsole steuert jetzt Spieler: §f" + target.getName());
            sender.sendMessage("§7Alle nächsten Befehle laufen als Spieler. Mit §fexit §7beenden.");
            return;
        }

        if (actingPlayerId == null) {
            return;
        }

        Player actingPlayer = Bukkit.getPlayer(actingPlayerId);
        event.setCancelled(true);

        if (actingPlayer == null || !actingPlayer.isOnline()) {
            sender.sendMessage("§cDer ausgewählte Spieler ist offline. join-Modus beendet.");
            actingPlayerId = null;
            return;
        }

        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        boolean ok = actingPlayer.performCommand(command);
        if (!ok) {
            sender.sendMessage("§cBefehl konnte als Spieler nicht ausgeführt werden: §f/" + command);
        }
    }
}
