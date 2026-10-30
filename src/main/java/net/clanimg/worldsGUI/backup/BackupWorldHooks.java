package net.clanimg.worldsGUI.backup;

import java.nio.file.Path;
import java.util.Optional;

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

    /** Template-Gruppe, wenn dieser Server eine Instanz davon ist (lobby für lobby-1), sonst null. Thread-sicher. */
    String localServerGroup();

    /**
     * Ordner im Template, in den eine Welt der eigenen Gruppe nach einem Restore zusätzlich geschrieben wird
     * (z.B. templates/lobby/&lt;welt&gt;), damit jede - auch künftige - Instanz sie hat; sonst leer. Thread-sicher,
     * fragt die API und wird deshalb nicht auf dem Hauptthread aufgerufen.
     */
    Optional<Path> templateWorldFolder(String worldName);

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

    /**
     * Nach dem Einspielen eines Duplikats auf einem anderen Server: Welt-Standards wie bei einer neu erstellten Welt
     * setzen (WorldGuard-Flags aus world-defaults.yml, z.B. leaf-decay, Gamerules, post-create-commands). WorldGuard
     * speichert Regionen pro Server, sie kommen mit dem Weltordner nicht mit.
     */
    void applyNewWorldDefaults(String worldName);

    /** Informiert den anfragenden Spieler über das Ergebnis seines Auftrags (Spieler ist online). */
    void notifyJobResult(Player player, BackupJob job);
}
