package net.clanimg.worldsGUI.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Merkt sich pro Welt, in welcher Phase ein Restore gerade steckt. Nach einem Absturz oder Neustart
 * mitten im Restore kann der Server daran erkennen, ob der Ordner-Tausch schon (teilweise) passiert ist.
 */
final class RestoreStateStore {
    enum Phase {
        /** Backup wird heruntergeladen/entpackt, die eigentliche Welt ist unberührt. */
        PREPARING,
        /** Ordner werden gerade getauscht. */
        SWAPPING,
        /** Tausch ist abgeschlossen, es fehlt nur noch Laden und Aufräumen. */
        DONE
    }

    record State(long jobId, Phase phase) {
    }

    private final Path directory;

    RestoreStateStore(Path directory) {
        this.directory = directory;
    }

    Optional<State> read(String worldName) {
        Path file = fileFor(worldName);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(file)) {
            Properties properties = new Properties();
            properties.load(in);
            return Optional.of(new State(
                Long.parseLong(properties.getProperty("job-id", "0")),
                Phase.valueOf(properties.getProperty("phase", Phase.PREPARING.name()))
            ));
        } catch (IOException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    void write(String worldName, long jobId, Phase phase) throws IOException {
        Files.createDirectories(directory);
        Properties properties = new Properties();
        properties.setProperty("job-id", Long.toString(jobId));
        properties.setProperty("phase", phase.name());

        Path target = fileFor(worldName);
        // Eindeutiger Temp-Name: eine liegengebliebene, evtl. fremd besessene ".state.tmp" blockiert sonst jeden Restore.
        Files.deleteIfExists(directory.resolve(worldName + ".state.tmp"));
        Path temp = Files.createTempFile(directory, worldName + ".state.", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                properties.store(out, "WorldsGUI restore state");
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    void clear(String worldName) {
        try {
            Files.deleteIfExists(fileFor(worldName));
        } catch (IOException ignored) {
            // Ein übrig gebliebener Eintrag wird beim nächsten Restore überschrieben.
        }
    }

    List<String> worldsWithState() {
        List<String> worlds = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return worlds;
        }
        try (Stream<Path> files = Files.list(directory)) {
            files.forEach(file -> {
                String name = file.getFileName().toString();
                if (name.endsWith(".state")) {
                    worlds.add(name.substring(0, name.length() - ".state".length()));
                }
            });
        } catch (IOException ignored) {
            // Kein Zugriff: dann gibt es nichts wiederherzustellen.
        }
        return worlds;
    }

    private Path fileFor(String worldName) {
        return directory.resolve(worldName + ".state");
    }
}
