package net.clanimg.worldsGUI.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.clanimg.worldsGUI.gui.GuiManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

public final class NavCommand implements TabExecutor {
    private final GuiManager guiManager;

    public NavCommand(GuiManager guiManager) {
        this.guiManager = guiManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Dieser Befehl ist nur für Spieler.");
            return true;
        }

        if (args.length == 0) {
            guiManager.executeNav(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> {
                if (args.length < 2) {
                    player.sendMessage("Usage: /nav create <A010>");
                    return true;
                }
                guiManager.executeNavTicketCreate(player, args[1]);
                return true;
            }
            case "delete" -> {
                if (args.length < 2) {
                    player.sendMessage("Usage: /nav delete <world>");
                    return true;
                }
                guiManager.executeNavDelete(player, args[1]);
                return true;
            }
            case "join" -> {
                if (args.length < 3) {
                    player.sendMessage("Usage: /nav join <enable|disable> <A010>");
                    return true;
                }
                String joinSub = args[1].toLowerCase(Locale.ROOT);
                switch (joinSub) {
                    case "enable" -> guiManager.executeNavJoinEnable(player, args[2]);
                    case "disable" -> guiManager.executeNavJoinDisable(player, args[2]);
                    default -> player.sendMessage("Usage: /nav join <enable|disable> <A010>");
                }
                return true;
            }
            case "my-world" -> {
                if (args.length < 2) {
                    player.sendMessage("Usage: /nav my-world <create|delete|open|closed|trust|untrust|rename|icon> ...");
                    return true;
                }
                String myWorldSub = args[1].toLowerCase(Locale.ROOT);
                switch (myWorldSub) {
                    case "create" -> {
                        if (args.length < 3) {
                            player.sendMessage("Usage: /nav my-world create <new-name>");
                            return true;
                        }
                        guiManager.executeNavMyWorldCreate(player, args[2]);
                        return true;
                    }
                    case "delete" -> {
                        if (args.length < 4 || !args[3].equalsIgnoreCase("confirm")) {
                            player.sendMessage("Usage: /nav my-world delete <world-name> confirm");
                            return true;
                        }
                        guiManager.executeNavMyWorldDelete(player, args[2], true);
                        return true;
                    }
                    case "open" -> {
                        if (args.length < 3) {
                            player.sendMessage("Usage: /nav my-world open <world-name>");
                            return true;
                        }
                        guiManager.executeNavMyWorldOpen(player, args[2]);
                        return true;
                    }
                    case "closed" -> {
                        if (args.length < 3) {
                            player.sendMessage("Usage: /nav my-world closed <world-name>");
                            return true;
                        }
                        guiManager.executeNavMyWorldClosed(player, args[2]);
                        return true;
                    }
                    case "trust" -> {
                        if (args.length < 4) {
                            player.sendMessage("Usage: /nav my-world trust <world-name> <player>");
                            return true;
                        }
                        guiManager.executeNavMyWorldTrust(player, args[2], args[3]);
                        return true;
                    }
                    case "untrust" -> {
                        if (args.length < 4) {
                            player.sendMessage("Usage: /nav my-world untrust <world-name> <player>");
                            return true;
                        }
                        guiManager.executeNavMyWorldUntrust(player, args[2], args[3]);
                        return true;
                    }
                    case "rename" -> {
                        if (args.length < 4) {
                            player.sendMessage("Usage: /nav my-world rename <world-name> <new-name>");
                            return true;
                        }
                        guiManager.executeNavMyWorldRename(player, args[2], slice(args, 3));
                        return true;
                    }
                    case "icon" -> {
                        if (args.length < 4) {
                            player.sendMessage("Usage: /nav my-world icon <world-name> <material>");
                            return true;
                        }
                        guiManager.executeNavMyWorldIcon(player, args[2], new String[] { args[3] });
                        return true;
                    }
                    default -> {
                        player.sendMessage("Usage: /nav my-world <create|delete|open|closed|trust|untrust|rename|icon> ...");
                        return true;
                    }
                }
            }
            case "confirm" -> {
                if (args.length < 2) {
                    player.sendMessage("Usage: /nav confirm <code>");
                    return true;
                }
                guiManager.executeNavConfirm(player, args[1]);
                return true;
            }
            case "customer" -> {
                if (args.length < 3) {
                    player.sendMessage("Usage: /nav customer <remove|trust|untrust> ...");
                    return true;
                }
                String customerSub = args[1].toLowerCase(Locale.ROOT);
                switch (customerSub) {
                    case "remove" -> {
                        if (args.length < 4) {
                            player.sendMessage("Usage: /nav customer remove <world> <player>");
                            return true;
                        }
                        guiManager.executeNavCustomerRemove(player, args[2], args[3]);
                        return true;
                    }
                    case "trust" -> {
                        if (args.length < 3) {
                            player.sendMessage("Usage: /nav customer trust <player>");
                            return true;
                        }
                        guiManager.executeNavCustomerTrust(player, args[2]);
                        return true;
                    }
                    case "untrust" -> {
                        if (args.length < 3) {
                            player.sendMessage("Usage: /nav customer untrust <player>");
                            return true;
                        }
                        guiManager.executeNavCustomerUntrust(player, args[2]);
                        return true;
                    }
                    default -> {
                        player.sendMessage("Usage: /nav customer <remove|trust|untrust> ...");
                        return true;
                    }
                }
            }
            case "setspawn" -> {
                String worldName = args.length >= 2 ? args[1] : null;
                guiManager.executeNavSetSpawn(player, worldName);
                return true;
            }
            case "rename" -> {
                if (args.length < 3) {
                    player.sendMessage("Usage: /nav rename <world> <name>");
                    return true;
                }
                String worldName = args[1];
                String[] renameArgs = slice(args, 2);
                guiManager.executeNavRename(player, worldName, renameArgs);
                return true;
            }
            case "icon" -> {
                if (args.length < 3) {
                    player.sendMessage("Usage: /nav icon <world> <material>");
                    return true;
                }
                String worldName = args[1];
                String[] iconArgs = new String[] { args[2] };
                guiManager.executeNavIcon(player, worldName, iconArgs);
                return true;
            }
            default -> {
                guiManager.executeNav(player);
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) {
            return List.of();
        }

        if (args.length == 1) {
            return filterPrefix(List.of("create", "delete", "join", "my-world", "confirm", "customer", "setspawn", "rename", "icon"), args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("join")) {
            return filterPrefix(List.of("enable", "disable"), args[1]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("my-world")) {
            return filterPrefix(List.of("create", "delete", "open", "closed", "trust", "untrust", "rename", "icon"), args[1]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("customer")) {
            return filterPrefix(List.of("remove", "trust", "untrust"), args[1]);
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("confirm")) {
                return List.of("<code>");
            }
            if (sub.equals("setspawn") || sub.equals("rename") || sub.equals("icon") || sub.equals("delete")) {
                return filterPrefix(guiManager.listOwnedWorldNames((Player) sender), args[1]);
            }
            if (sub.equals("create")) {
                return List.of("A010");
            }
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("join")) {
            return List.of("<A010>");
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("my-world") && args[1].equalsIgnoreCase("delete")) {
            return List.of("confirm");
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("my-world") && args[1].equalsIgnoreCase("create")) {
            return List.of("<new-name>");
        }

        if (args[0].equalsIgnoreCase("customer")) {
            String customerSub = args[1].toLowerCase(Locale.ROOT);
            if (args.length == 3) {
                if (customerSub.equals("invite") || customerSub.equals("remove")) {
                    return filterPrefix(guiManager.listOwnedWorldNames((Player) sender), args[2]);
                }
                if (customerSub.equals("trust")) {
                    return filterPrefix(guiManager.listContextInvitedPlayerNames((Player) sender), args[2]);
                }
                if (customerSub.equals("untrust")) {
                    return filterPrefix(guiManager.listContextTrustedPlayerNames((Player) sender), args[2]);
                }
            }
            if (args.length == 4) {
                if (customerSub.equals("invite")) {
                    return filterPrefix(onlinePlayers(), args[3]);
                }
                if (customerSub.equals("remove")) {
                    return filterPrefix(guiManager.listInvitedPlayerNames(args[2]), args[3]);
                }
            }
        }

        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("rename")) {
                return List.of("<name>");
            }
            if (sub.equals("icon")) {
                return List.of("<material>");
            }
            if (sub.equals("create")) {
                return List.of("A010");
            }
        }

        return List.of();
    }

    private String[] slice(String[] args, int start) {
        String[] out = new String[Math.max(0, args.length - start)];
        System.arraycopy(args, start, out, 0, out.length);
        return out;
    }

    private List<String> filterPrefix(List<String> source, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String candidate : source) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(normalized)) {
                out.add(candidate);
            }
        }
        return out;
    }

    private List<String> onlinePlayers() {
        List<String> out = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            out.add(online.getName());
        }
        return out;
    }
}
