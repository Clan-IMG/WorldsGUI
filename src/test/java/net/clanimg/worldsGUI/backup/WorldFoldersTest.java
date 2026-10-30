package net.clanimg.worldsGUI.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldFoldersTest {
    @TempDir
    Path dir;

    @Test
    void copiesTheWorldWithoutSessionLockAndReplacesAnOlderCopy() throws Exception {
        Path source = dir.resolve("server/WrobelXXL-1");
        Files.createDirectories(source.resolve("region"));
        Files.writeString(source.resolve("level.dat"), "neu");
        Files.writeString(source.resolve("region/r.0.0.mca"), "chunks");
        Files.writeString(source.resolve("session.lock"), "lock");

        Path target = dir.resolve("templates/lobby/WrobelXXL-1");
        Files.createDirectories(target);
        Files.writeString(target.resolve("level.dat"), "alt");
        Files.writeString(target.resolve("alt-nur-im-template.txt"), "weg damit");

        WorldFolders.replaceWithCopy(source, target);

        assertEquals("neu", Files.readString(target.resolve("level.dat")));
        assertEquals("chunks", Files.readString(target.resolve("region/r.0.0.mca")));
        assertFalse(Files.exists(target.resolve("session.lock")));
        assertFalse(Files.exists(target.resolve("alt-nur-im-template.txt")));
        try (Stream<Path> leftovers = Files.list(target.getParent())) {
            assertEquals(1, leftovers.count(), "keine Zwischenordner übrig");
        }
        assertTrue(Files.exists(source.resolve("session.lock")), "Quelle bleibt unverändert");
    }
}
