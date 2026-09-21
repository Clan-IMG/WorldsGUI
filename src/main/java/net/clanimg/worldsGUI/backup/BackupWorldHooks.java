package net.clanimg.worldsGUI.backup;

import net.clanimg.worldsGUI.data.WorldsRepository.BackupJob;
import org.bukkit.entity.Player;

/**
 * Zugriffe auf die Bukkit-Welt und Spieler, die der Backup-Dienst braucht. Alle Methoden außer
 * {@link #localServerName()} werden garantiert auf dem Server-Hauptthread aufgerufen (der Dienst
 * reiht sie dort ein und wartet).
 */
public interface BackupWorldHooks {
    /** Server-ID dieses Servers (wie in der Welten-Tabelle hinterlegt), leer wenn unbekannt. Thread-sicher. */
    String localServerName();

    /**
     * Speichert die Welt und schaltet ihr Autosave aus, damit die Dateien während des Zippens ruhig bleiben.
     *
     * @return vorheriger Autosave-Zustand, oder null wenn die Welt gerade nicht geladen ist
     */
    Boolean pauseAutoSave(String worldName);

    void resumeAutoSave(String worldName, boolean previousState);

    /** Schickt Spieler aus der Welt und entlädt sie (mit Speichern). @return true, wenn die Welt danach nicht mehr geladen ist */
    boolean unloadWorldForRestore(String worldName);

    /** Lädt die Welt nach einem Restore wieder. @return true, wenn sie danach geladen ist */
    boolean loadWorld(String worldName);

    /** Setzt den Weltschutz (WorldGuard) nach einem Restore neu. */
    void refreshWorldProtection(String worldName);

    /** Informiert den anfragenden Spieler über das Ergebnis seines Auftrags (Spieler ist online). */
    void notifyJobResult(Player player, BackupJob job);
}
