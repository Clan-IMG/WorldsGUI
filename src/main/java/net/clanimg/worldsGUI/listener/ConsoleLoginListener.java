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

    public String activate(String targetName) {
        String normalizedName = targetName == null ? "" : targetName.trim();
        if (normalizedName.isEmpty()) {
            return "§cUsage: login <player>";
        }

        Player target = Bukkit.getPlayerExact(normalizedName);
        if (target == null || !target.isOnline()) {
            return "§cSpieler nicht online: " + normalizedName;
        }

        if (actingPlayerId != null) {
            Player current = Bukkit.getPlayer(actingPlayerId);
            if (current != null && current.isOnline()) {
                if (current.getUniqueId().equals(target.getUniqueId())) {
                    return "§ejoin-Modus ist bereits aktiv für §f" + current.getName() + "§e.";
                }
                return "§ejoin-Modus ist bereits aktiv für §f" + current.getName()
                    + "§e. Nutze zuerst §fexit§e, dann §flogin " + target.getName() + "§e.";
            }
            actingPlayerId = null;
        }

        actingPlayerId = target.getUniqueId();
        return "§aKonsole steuert jetzt Spieler: §f" + target.getName() + "\n§7Alle nächsten Befehle laufen als Spieler. Mit §fexit §7beenden.";
    }

    public String deactivate() {
        if (actingPlayerId == null) {
            return "§eKein aktiver join-Modus.";
        }
        actingPlayerId = null;
        return "§ajoin-Modus beendet. Du bist wieder normale Konsole.";
    }

    public boolean hasActivePlayer() {
        return actingPlayerId != null;
    }

    public String handleConsoleCommand(String rawCommand) {
        String raw = rawCommand == null ? "" : rawCommand.trim();
        if (raw.isEmpty()) {
            return null;
        }

        String lower = raw.toLowerCase(Locale.ROOT);

        if (lower.equals("exit")) {
            return deactivate();
        }

        if (lower.startsWith("join ") || lower.startsWith("login ")) {
            String targetName;
            if (lower.startsWith("join ")) {
                targetName = raw.substring("join ".length()).trim();
            } else {
                targetName = raw.substring("login ".length()).trim();
            }
            return activate(targetName);
        }

        if (actingPlayerId == null) {
            return null;
        }

        Player actingPlayer = Bukkit.getPlayer(actingPlayerId);
        if (actingPlayer == null || !actingPlayer.isOnline()) {
            actingPlayerId = null;
            return "§cDer ausgewählte Spieler ist offline. join-Modus beendet.";
        }

        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        boolean ok = actingPlayer.performCommand(command);
        if (!ok) {
            return "§cBefehl konnte als Spieler nicht ausgeführt werden: §f/" + command;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        CommandSender sender = event.getSender();
        if (!(sender instanceof ConsoleCommandSender)) {
            return;
        }

        if (event.getCommand() == null || event.getCommand().trim().isEmpty()) {
            return;
        }

        String result = handleConsoleCommand(event.getCommand());
        if (result == null) {
            return;
        }
        event.setCancelled(true);
        for (String line : result.split("\\n")) {
            sender.sendMessage(line);
        }
    }
}
