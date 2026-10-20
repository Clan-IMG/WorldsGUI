package net.clanimg.worldsGUI.idle;

/**
 * Reine Entscheidungslogik von {@link IdleWorldUnloader}, getrennt von Bukkit gehalten, damit sie
 * ohne laufenden Server testbar ist.
 */
public final class IdleUnloadPolicy {
    private IdleUnloadPolicy() {
    }

    /**
     * Ob eine seit {@code emptySinceMillis} leere Welt jetzt entladen werden soll.
     *
     * @param emptySinceMillis Zeitpunkt (epoch millis), seit dem die Welt ununterbrochen leer ist
     * @param nowMillis        aktueller Zeitpunkt (epoch millis)
     * @param delaySeconds     Mindest-Wartezeit in Sekunden, bevor eine leere private Welt entladen
     *                         wird (schützt vor sofortigem Entladen bei kurzem Reconnect/Lag)
     */
    public static boolean shouldUnload(long emptySinceMillis, long nowMillis, int delaySeconds) {
        if (delaySeconds <= 0) {
            return nowMillis >= emptySinceMillis;
        }
        return nowMillis - emptySinceMillis >= delaySeconds * 1000L;
    }
}
