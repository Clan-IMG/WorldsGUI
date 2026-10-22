package net.clanimg.worldsGUI.idle;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entlädt WorldsGUI-eigene private (einem einzelnen Spieler gehörende) Welten, sobald niemand
 * mehr darin ist – nicht sofort, sondern erst nach {@code idle-unload.delay-seconds} ohne Spieler,
 * damit ein kurzer Disconnect/Reconnect (Lag, Clientabsturz) nicht sofort ein Neuladen auslöst.
 *
 * <p>Bewusst eng begrenzt: Diese Klasse entlädt ausschließlich Welten, die ihr vorher explizit per
 * {@link #trackPrivateWorld(String)} gemeldet wurden – das passiert nur an den Stellen in
 * {@code GuiManager}, an denen ein Spieler gerade erfolgreich in seine EIGENE private Welt gestellt
 * wurde (Erstellung beim Erst-Join, oder erneutes Betreten z. B. über {@code /nav}). Öffentliche
 * Welten, Ticket-Welten mit mehreren Kunden, die Lobby-/Hauptwelt und Lasertag-Matchinstanzen
 * (die ihre eigene Entlade-Logik haben) werden nie angefasst, weil sie nie in dieses Set
 * aufgenommen werden. Ruft dafür keine zusätzlichen API-Aufrufe auf: der Aufrufer hat die
 * nötigen Informationen (Weltname, sourceType "private") an jeder Registrierungsstelle schon
 * lokal vorliegen.
 */
public final class IdleWorldUnloader {
    private final JavaPlugin plugin;
    private final IdleUnloadSettings settings;

    /** Namen der Welten, die wir für Idle-Unload im Blick behalten. */
    private final Set<String> managedPrivateWorlds = ConcurrentHashMap.newKeySet();
    /** worldName -> seit wann (epoch millis) sie ununterbrochen leer ist. */
    private final Map<String, Long> emptySince = new ConcurrentHashMap<>();

    public IdleWorldUnloader(JavaPlugin plugin, IdleUnloadSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public boolean isEnabled() {
        return settings.enabled();
    }

    public int checkIntervalSeconds() {
        return settings.checkIntervalSeconds();
    }

    /**
     * Meldet an, dass {@code worldName} eine private Einzelspieler-Welt ist, die entladen werden
     * darf, sobald sie eine Weile leer war. Mehrfachaufrufe für denselben Namen sind unbedenklich.
     * Ohne Wirkung, wenn das Feature per config.yml abgeschaltet ist.
     */
    public void trackPrivateWorld(String worldName) {
        if (!settings.enabled() || worldName == null || worldName.isBlank()) {
            return;
        }
        managedPrivateWorlds.add(worldName);
    }

    /** Nimmt eine Welt wieder aus der Beobachtung (z.B. wenn sie anderweitig gelöscht wurde). */
    public void forget(String worldName) {
        managedPrivateWorlds.remove(worldName);
        emptySince.remove(worldName);
    }

    /**
     * Periodisch aufzurufen (siehe {@code WorldsGUI#onEnable}). Entlädt jede beobachtete, aktuell
     * geladene private Welt, die seit mindestens {@code idle-unload.delay-seconds} leer ist.
     */
    public void sweep() {
        if (!settings.enabled() || managedPrivateWorlds.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        World primary = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);

        for (String worldName : managedPrivateWorlds) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                // schon anderweitig entladen/gelöscht - nichts mehr zu tun
                managedPrivateWorlds.remove(worldName);
                emptySince.remove(worldName);
                continue;
            }
            if (world.equals(primary)) {
                // Verteidigung in der Tiefe: die Hauptwelt darf nie automatisch entladen werden,
                // auch wenn sie (fälschlich) in diesem Set gelandet wäre.
                managedPrivateWorlds.remove(worldName);
                emptySince.remove(worldName);
                continue;
            }
            if (!world.getPlayers().isEmpty()) {
                emptySince.remove(worldName); // wieder jemand drin - Gnadenfrist zurücksetzen
                continue;
            }

            long since = emptySince.computeIfAbsent(worldName, key -> now);
            if (!IdleUnloadPolicy.shouldUnload(since, now, settings.delaySeconds())) {
                continue;
            }

            managedPrivateWorlds.remove(worldName);
            emptySince.remove(worldName);
            if (Bukkit.unloadWorld(world, true)) {
                plugin.getLogger().info("WorldsGUI: private Welt '" + worldName + "' nach "
                    + settings.delaySeconds() + "s ohne Spieler entladen (idle-unload).");
            } else {
                plugin.getLogger().warning("WorldsGUI: konnte leere private Welt '" + worldName
                    + "' nicht entladen (Bukkit.unloadWorld verweigert) - versuche es beim "
                    + "nächsten Durchlauf erneut.");
                managedPrivateWorlds.add(worldName); // erneut versuchen, nicht stillschweigend aufgeben
            }
        }
    }
}
