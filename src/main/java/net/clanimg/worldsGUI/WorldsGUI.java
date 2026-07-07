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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WorldsGUI extends JavaPlugin {
    private WorldsRepository repository;
    private GuiManager guiManager;
    private BukkitTask joinRequestTask;
    private BukkitTask presenceRefreshTask;
    private BukkitTask ticketSyncTask;
    private ConsoleLoginListener consoleLoginListener;
    private final AtomicBoolean joinRequestPollRunning = new AtomicBoolean(false);

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
            getLogger().warning("API konnte nicht initialisiert werden: " + repository.lastInitializeError());
            getLogger().warning("WorldsGUI bleibt aktiv und versucht die API später erneut zu erreichen.");
        }

        guiManager = new GuiManager(this, repository);

        consoleLoginListener = new ConsoleLoginListener();

        Bukkit.getPluginManager().registerEvents(new InventoryListener(guiManager), this);
        Bukkit.getPluginManager().registerEvents(new ChatInputListener(guiManager), this);
        Bukkit.getPluginManager().registerEvents(consoleLoginListener, this);
        Bukkit.getPluginManager().registerEvents(new PlayerPresenceListener(this, repository), this);

        joinRequestTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::processJoinRequests, 40L, 40L);
        presenceRefreshTask = Bukkit.getScheduler().runTaskTimer(this, this::refreshOnlinePresence, 20L, 20L * 20L);
        ticketSyncTask = Bukkit.getScheduler().runTaskTimer(this, this::syncTicketWorlds, 20L * 10L, 20L * 30L);

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
        if (presenceRefreshTask != null) {
            presenceRefreshTask.cancel();
            presenceRefreshTask = null;
        }
        if (ticketSyncTask != null) {
            ticketSyncTask.cancel();
            ticketSyncTask = null;
        }
        if (guiManager != null) {
            guiManager.shutdown();
        }
    }

    private void refreshOnlinePresence() {
        List<PresenceSnapshot> snapshots = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            snapshots.add(new PresenceSnapshot(player.getName(), player.getWorld().getName()));
        }
        if (snapshots.isEmpty()) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            for (PresenceSnapshot snapshot : snapshots) {
                repository.upsertPlayerPresence(snapshot.playerName(), snapshot.worldName(), true);
            }
        });
    }

    private void processJoinRequests() {
        if (!joinRequestPollRunning.compareAndSet(false, true)) {
            return;
        }

        try {
            java.util.List<WorldsRepository.JoinRequest> requests = repository.listPendingJoinRequests(30);
            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    for (WorldsRepository.JoinRequest request : requests) {
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
                } finally {
                    joinRequestPollRunning.set(false);
                }
            });
        } catch (Exception ex) {
            joinRequestPollRunning.set(false);
            getLogger().warning("Fehler beim Laden der Join-Requests via API: " + ex.getMessage());
        }
    }

    private void syncTicketWorlds() {
        if (guiManager == null) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            guiManager.syncAssignedTicketWorlds(player);
        }
    }

    private void disablePluginWithReason(String reason) {
        getLogger().severe(reason);
        getLogger().severe("WorldsGUI wird deaktiviert.");
        Bukkit.getPluginManager().disablePlugin(this);
    }

    private record PresenceSnapshot(String playerName, String worldName) {
    }
}
