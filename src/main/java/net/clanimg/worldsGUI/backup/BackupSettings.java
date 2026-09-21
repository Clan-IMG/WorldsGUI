package net.clanimg.worldsGUI.backup;

import java.time.DateTimeException;
import java.time.ZoneId;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Snapshot des "backup:"-Abschnitts aus config.yml. Alle Werte haben Standardwerte, damit auch
 * eine ältere config.yml ohne diesen Abschnitt weiterhin funktioniert.
 */
public record BackupSettings(
    boolean enabled,
    int pollIntervalSeconds,
    int heartbeatIntervalSeconds,
    int notifyIntervalSeconds,
    int maxParallelJobs,
    int minPartSizeMb,
    int compressionLevel,
    int partUploadRetries,
    ZoneId displayZone
) {
    private static final String DEFAULT_ZONE = "Europe/Berlin";

    public static BackupSettings from(FileConfiguration config) {
        ZoneId zone;
        try {
            zone = ZoneId.of(config.getString("backup.display-timezone", DEFAULT_ZONE));
        } catch (DateTimeException ex) {
            zone = ZoneId.of(DEFAULT_ZONE);
        }

        return new BackupSettings(
            config.getBoolean("backup.enabled", true),
            Math.max(5, config.getInt("backup.poll-interval-seconds", 10)),
            clamp(config.getInt("backup.heartbeat-interval-seconds", 30), 10, 120),
            Math.max(5, config.getInt("backup.notify-interval-seconds", 10)),
            clamp(config.getInt("backup.max-parallel-jobs", 1), 1, 4),
            clamp(config.getInt("backup.min-part-size-mb", 16), 5, 256),
            clamp(config.getInt("backup.compression-level", 6), 1, 9),
            clamp(config.getInt("backup.part-upload-retries", 5), 1, 10),
            zone
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
