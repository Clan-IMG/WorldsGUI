package net.clanimg.worldsGUI.backup;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Formatierung von Größen und Zeitangaben für GUI und Chat. */
public final class BackupFormat {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY);

    private BackupFormat() {
    }

    public static String size(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes / 1024.0;
        String[] units = { "KB", "MB", "GB", "TB" };
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format(Locale.GERMANY, value >= 100 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    public static String date(String isoInstant, ZoneId zone) {
        Instant instant = parse(isoInstant);
        return instant == null ? "?" : DATE_FORMAT.format(instant.atZone(zone));
    }

    public static String age(String isoInstant) {
        Instant instant = parse(isoInstant);
        if (instant == null) {
            return "?";
        }
        Duration age = Duration.between(instant, Instant.now());
        long minutes = Math.max(0L, age.toMinutes());
        if (minutes < 1) {
            return "wenigen Sekunden";
        }
        if (minutes < 60) {
            return minutes + (minutes == 1 ? " Minute" : " Minuten");
        }
        long hours = minutes / 60;
        if (hours < 48) {
            return hours + (hours == 1 ? " Stunde" : " Stunden");
        }
        long days = hours / 24;
        return days + " Tagen";
    }

    private static Instant parse(String isoInstant) {
        if (isoInstant == null || isoInstant.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(isoInstant);
        } catch (DateTimeException ex) {
            return null;
        }
    }
}
