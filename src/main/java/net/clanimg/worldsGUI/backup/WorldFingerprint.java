package net.clanimg.worldsGUI.backup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * Fingerabdruck eines Weltordners (Pfad, Größe, Änderungszeit aller Dateien). Damit erkennt das
 * nächtliche Backup, ob sich die Welt seit dem letzten Auto-Backup überhaupt geändert hat.
 * level.dat wird bewusst ignoriert, weil sich dort z.B. die Tageszeit ständig ändert.
 */
public record WorldFingerprint(String fingerprint, long totalBytes, int fileCount) {
    /** Dateien, die nie ins Archiv gehören (Sperrdatei, UID wird vom Server neu erzeugt). */
    static boolean isExcludedFromArchive(String relativePath) {
        return relativePath.equals("session.lock") || relativePath.equals("uid.dat");
    }

    private static boolean isExcludedFromFingerprint(String relativePath) {
        return isExcludedFromArchive(relativePath)
            || relativePath.equals("level.dat")
            || relativePath.equals("level.dat_old");
    }

    public static WorldFingerprint compute(Path folder) throws IOException {
        List<String> lines = new ArrayList<>();
        long[] total = new long[1];
        int[] count = new int[1];

        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String relative = folder.relativize(file).toString().replace('\\', '/');
                total[0] += attrs.size();
                count[0]++;
                if (!isExcludedFromFingerprint(relative)) {
                    lines.add(relative + "|" + attrs.size() + "|" + attrs.lastModifiedTime().toMillis());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // Datei ist zwischen Auflisten und Lesen verschwunden (z.B. Chunk-Datei wurde ersetzt).
                return FileVisitResult.CONTINUE;
            }
        });

        Collections.sort(lines);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                digest.update(line.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return new WorldFingerprint(HexFormat.of().formatHex(digest.digest()), total[0], count[0]);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", ex);
        }
    }
}
