package net.clanimg.worldsGUI;

import net.clanimg.worldsGUI.command.IconCommand;
import net.clanimg.worldsGUI.command.NavCommand;
import net.clanimg.worldsGUI.command.RenameCommand;
import net.clanimg.worldsGUI.command.SetSpawnCommand;
import net.clanimg.worldsGUI.command.UnverifyCommand;
import net.clanimg.worldsGUI.command.VerifyCommand;
import net.clanimg.worldsGUI.data.WorldsRepository;
import net.clanimg.worldsGUI.gui.GuiManager;
import net.clanimg.worldsGUI.listener.ChatInputListener;
import net.clanimg.worldsGUI.listener.ConsoleLoginListener;
import net.clanimg.worldsGUI.listener.InventoryListener;
import net.clanimg.worldsGUI.listener.PlayerPresenceListener;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class WorldsGUI extends JavaPlugin {
    private WorldsRepository repository;
    private GuiManager guiManager;
    private BukkitTask joinRequestTask;
    private ConsoleLoginListener consoleLoginListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String apiBaseUrl = getConfig().getString("api.base-url", "");
        String apiToken = getConfig().getString("api.token", "");

        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            disablePluginWithReason("API base-url ist leer oder fehlt in config.yml (api.base-url).");
            return;
        }
        if (apiToken == null || apiToken.isBlank()) {
            disablePluginWithReason("API token ist leer oder fehlt in config.yml (api.token).");
            return;
        }

        repository = new WorldsRepository(this, apiBaseUrl, apiToken);
        if (!repository.initialize()) {
            disablePluginWithReason("API konnte nicht initialisiert werden: " + repository.lastInitializeError());
            return;
        }

        guiManager = new GuiManager(this, repository);

        consoleLoginListener = new ConsoleLoginListener();

        Bukkit.getPluginManager().registerEvents(new InventoryListener(guiManager), this);
        Bukkit.getPluginManager().registerEvents(new ChatInputListener(guiManager), this);
        Bukkit.getPluginManager().registerEvents(consoleLoginListener, this);
        Bukkit.getPluginManager().registerEvents(new PlayerPresenceListener(repository), this);

        joinRequestTask = Bukkit.getScheduler().runTaskTimer(this, this::processJoinRequests, 40L, 40L);

        if (getCommand("nav") != null) {
            NavCommand navCommand = new NavCommand(guiManager);
            getCommand("nav").setExecutor(navCommand);
            getCommand("nav").setTabCompleter(navCommand);
        }
        if (getCommand("setspawn") != null) {
            getCommand("setspawn").setExecutor(new SetSpawnCommand(guiManager));
        }
        if (getCommand("rename") != null) {
            getCommand("rename").setExecutor(new RenameCommand(guiManager));
        }
        if (getCommand("icon") != null) {
            getCommand("icon").setExecutor(new IconCommand(guiManager));
        }
        if (getCommand("verify") != null) {
            getCommand("verify").setExecutor(new VerifyCommand(guiManager));
        }
        if (getCommand("unverify") != null) {
            getCommand("unverify").setExecutor(new UnverifyCommand(guiManager));
        }
        if (getCommand("login") != null) {
            getCommand("login").setExecutor((CommandExecutor) (sender, command, label, args) -> {
                if (sender instanceof org.bukkit.command.ConsoleCommandSender) {
                    String targetName = args.length >= 1 ? String.join(" ", args) : "";
                    String result = consoleLoginListener.activate(targetName);
                    if (result != null) {
                        for (String line : result.split("\\n")) {
                            sender.sendMessage(line);
                        }
                    }
                    return true;
                }
                sender.sendMessage("Dieser Befehl ist nur für die Konsole.");
                return true;
            });
        }
        if (getCommand("exit") != null) {
            getCommand("exit").setExecutor((sender, command, label, args) -> {
                if (sender instanceof org.bukkit.command.ConsoleCommandSender) {
                    String result = consoleLoginListener.deactivate();
                    if (result != null) {
                        sender.sendMessage(result);
                    }
                    return true;
                }
                sender.sendMessage("Dieser Befehl ist nur für die Konsole.");
                return true;
            });
        }
    }

    @Override
    public void onDisable() {
        if (joinRequestTask != null) {
            joinRequestTask.cancel();
            joinRequestTask = null;
        }
        if (guiManager != null) {
            guiManager.shutdown();
        }
    }

    private void processJoinRequests() {
        for (WorldsRepository.JoinRequest request : repository.listPendingJoinRequests(30)) {
            String transferCommand = buildVelocityTransferCommand(request.playerName(), request.worldName());
            if (transferCommand != null) {
                boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), transferCommand);
                if (dispatched) {
                    repository.markJoinRequest(request.id(), "done", "Velocity transfer command dispatched");
                } else {
                    repository.markJoinRequest(request.id(), "failed", "Velocity transfer command failed");
                }
                continue;
            }

            Player player = Bukkit.getPlayerExact(request.playerName());
            if (player == null || !player.isOnline()) {
                repository.markJoinRequest(request.id(), "failed", "Player offline");
                continue;
            }

            if (player.getWorld().getName().equalsIgnoreCase(request.worldName())) {
                repository.markJoinRequest(request.id(), "done", "Already in world");
                continue;
            }

            boolean ok = guiManager.executeDashboardJoin(player, request.worldName());
            if (ok) {
                repository.markJoinRequest(request.id(), "done", "Teleported");
            } else {
                repository.markJoinRequest(request.id(), "failed", "Join failed");
            }
        }
    }

    private String buildVelocityTransferCommand(String playerName, String worldName) {
        String template = getConfig().getString("velocity.transfer-command", "");
        if (template == null || template.isBlank()) {
            return null;
        }

        String command = template
            .replace("%player%", playerName)
            .replace("%world%", worldName)
            .trim();
        return command.isBlank() ? null : command;
    }

    private void disablePluginWithReason(String reason) {
        getLogger().severe(reason);
        getLogger().severe("WorldsGUI wird deaktiviert.");
        Bukkit.getPluginManager().disablePlugin(this);
    }
}
