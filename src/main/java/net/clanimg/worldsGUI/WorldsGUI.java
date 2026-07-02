package net.clanimg.worldsGUI;

import net.clanimg.worldsGUI.command.IconCommand;
import net.clanimg.worldsGUI.command.NavCommand;
import net.clanimg.worldsGUI.command.RenameCommand;
import net.clanimg.worldsGUI.command.SetSpawnCommand;
import net.clanimg.worldsGUI.data.WorldsRepository;
import net.clanimg.worldsGUI.gui.GuiManager;
import net.clanimg.worldsGUI.listener.ChatInputListener;
import net.clanimg.worldsGUI.listener.InventoryListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class WorldsGUI extends JavaPlugin {
    private WorldsRepository repository;
    private GuiManager guiManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String host = getConfig().getString("mysql.host", "localhost");
        int port = getConfig().getInt("mysql.port", 3306);
        String database = getConfig().getString("mysql.database", "worldsgui");
        String username = getConfig().getString("mysql.username", "worldsgui_user");
        String password = getConfig().getString("mysql.password", "");

        if (database == null || database.isBlank()) {
            disablePluginWithReason("MySQL database ist leer oder fehlt in config.yml (mysql.database).");
            return;
        }
        if (host == null || host.isBlank()) {
            disablePluginWithReason("MySQL host ist leer oder fehlt in config.yml (mysql.host).");
            return;
        }
        if (username == null || username.isBlank()) {
            disablePluginWithReason("MySQL username ist leer oder fehlt in config.yml (mysql.username).");
            return;
        }

        repository = new WorldsRepository(this, host, port, database, username, password);
        if (!repository.initialize()) {
            disablePluginWithReason("MySQL/MariaDB konnte nicht initialisiert werden: " + repository.lastInitializeError());
            return;
        }

        guiManager = new GuiManager(this, repository);

        Bukkit.getPluginManager().registerEvents(new InventoryListener(guiManager), this);
        Bukkit.getPluginManager().registerEvents(new ChatInputListener(guiManager), this);

        if (getCommand("nav") != null) {
            getCommand("nav").setExecutor(new NavCommand(guiManager));
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
    }

    @Override
    public void onDisable() {
        if (guiManager != null) {
            guiManager.shutdown();
        }
    }

    private void disablePluginWithReason(String reason) {
        getLogger().severe(reason);
        getLogger().severe("WorldsGUI wird deaktiviert.");
        Bukkit.getPluginManager().disablePlugin(this);
    }
}
