package net.clanimg.worldsGUI.api;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Öffentliche Addon-Schnittstelle von WorldsGUI. Wird beim Start im Bukkit-ServicesManager
 * registriert. Addons (z.B. MapMaker) greifen ohne Compile-Abhängigkeit per Reflection darauf zu,
 * daher enthalten die Signaturen ausschließlich JDK-Typen.
 */
public interface WorldsGuiAddonApi {
    int apiVersion();                       // 1

    String localServerName();

    /** Keys: worldName, displayName, ownerUuid, ownerName, serverName, invitedPlayers (List<String>),
     *  trustedPlayers (List<String>), public (Boolean), archived (Boolean), spawnX, spawnY, spawnZ (Double|null). */
    CompletableFuture<Optional<Map<String, Object>>> lookupWorld(String worldName);
}
