package net.clanimg.worldsGUI.backup;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/** Kopiert Weltordner, z.B. zwischen einem Server und seinem Template (ohne session.lock). */
public final class WorldFolders {
    private static final String SESSION_LOCK = "session.lock";

    private WorldFolders() {
    }

    /**
     * Ersetzt {@code target} durch eine Kopie von {@code source}. Kopiert wird erst in einen Nachbarordner und dann
     * umbenannt: Wer gleichzeitig aus {@code target} liest (z.B. SimpleCloud beim Start einer Instanz), sieht nie eine
     * halbe Welt. Schlägt das Umbenennen fehl, bleibt der vorherige Stand erhalten.
     */
    public static void replaceWithCopy(Path source, Path target) throws IOException {
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        Files.createDirectories(parent);
        String suffix = Long.toString(System.nanoTime());
        Path staging = parent.resolve("." + absoluteTarget.getFileName() + ".copy-" + suffix);
        Path previous = parent.resolve("." + absoluteTarget.getFileName() + ".old-" + suffix);
        try {
            copyTree(source, staging);
        } catch (IOException ex) {
            deleteTree(staging);
            throw ex;
        }
        boolean hadTarget = Files.exists(absoluteTarget);
        if (hadTarget) {
            Files.move(absoluteTarget, previous, StandardCopyOption.ATOMIC_MOVE);
        }
        try {
            Files.move(staging, absoluteTarget, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            if (hadTarget) {
                Files.move(previous, absoluteTarget, StandardCopyOption.ATOMIC_MOVE);
            }
            deleteTree(staging);
            throw ex;
        }
        deleteTree(previous);
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!SESSION_LOCK.equals(file.getFileName().toString())) {
                    Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
