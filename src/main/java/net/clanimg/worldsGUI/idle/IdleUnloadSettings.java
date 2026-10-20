package net.clanimg.worldsGUI.idle;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Snapshot des "idle-unload:"-Abschnitts aus config.yml. Alle Werte haben Standardwerte, damit
 * auch eine ältere config.yml ohne diesen Abschnitt weiterhin funktioniert (Feature bleibt an).
 */
public record IdleUnloadSettings(
    boolean enabled,
    int delaySeconds,
    int checkIntervalSeconds
) {
    public static IdleUnloadSettings from(FileConfiguration config) {
        return new IdleUnloadSettings(
            config.getBoolean("idle-unload.enabled", true),
            Math.max(30, config.getInt("idle-unload.delay-seconds", 300)),
            Math.max(10, config.getInt("idle-unload.check-interval-seconds", 60))
        );
    }
}
