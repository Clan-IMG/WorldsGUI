package net.clanimg.worldsGUI.backup;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import net.clanimg.worldsGUI.data.WorldsRepository;
import net.clanimg.worldsGUI.data.WorldsRepository.BackupPart;

/**
 * Nimmt den Zip-Datenstrom entgegen und lädt ihn in gleich großen Teilen (S3-Multipart) direkt zu R2 hoch.
 * Es wird keine Zip-Datei auf der Platte zwischengespeichert, nur ein Teil-Puffer im Arbeitsspeicher.
 * Die Prüfsumme (SHA-256) wird über den gesamten Datenstrom berechnet.
 */
final class PartUploadOutputStream extends OutputStream {
    private static final Duration PART_REQUEST_TIMEOUT = Duration.ofMinutes(30);

    private final WorldsRepository repository;
    private final HttpClient httpClient;
    private final JobContext context;
    private final long backupId;
    private final int retries;
    private final byte[] buffer;
    private final MessageDigest digest;
    private final List<BackupPart> parts = new ArrayList<>();

    private int filled;
    private int nextPartNumber = 1;
    private long totalBytes;
    private boolean closed;

    PartUploadOutputStream(
        WorldsRepository repository,
        HttpClient httpClient,
        JobContext context,
        long backupId,
        int partSizeBytes,
        int retries
    ) {
        this.repository = repository;
        this.httpClient = httpClient;
        this.context = context;
        this.backupId = backupId;
        this.retries = retries;
        this.buffer = new byte[partSizeBytes];
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", ex);
        }
    }

    @Override
    public void write(int value) throws IOException {
        write(new byte[] { (byte) value }, 0, 1);
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
        if (closed) {
            throw new IOException("Upload-Stream ist bereits geschlossen");
        }
        digest.update(data, offset, length);
        totalBytes += length;

        int remaining = length;
        int position = offset;
        while (remaining > 0) {
            int chunk = Math.min(remaining, buffer.length - filled);
            System.arraycopy(data, position, buffer, filled, chunk);
            filled += chunk;
            position += chunk;
            remaining -= chunk;
            if (filled == buffer.length) {
                flushPart();
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        // Der letzte Teil darf kleiner sein; bei komplett leerem Strom wird ein leerer Teil 1 hochgeladen.
        if (filled > 0 || nextPartNumber == 1) {
            flushPart();
        }
        closed = true;
    }

    List<BackupPart> parts() {
        return List.copyOf(parts);
    }

    long totalBytes() {
        return totalBytes;
    }

    String sha256() {
        return HexFormat.of().formatHex(digest.digest());
    }

    private void flushPart() throws IOException {
        String etag = uploadPart(nextPartNumber, filled);
        parts.add(new BackupPart(nextPartNumber, etag));
        nextPartNumber++;
        filled = 0;
    }

    private String uploadPart(int partNumber, int length) throws IOException {
        String lastError = "unbekannt";
        for (int attempt = 1; attempt <= retries; attempt++) {
            context.checkCancelled();

            // Signierte URL pro Versuch neu anfordern, falls die alte abgelaufen ist.
            Optional<String> url = repository.requestBackupPartUrl(backupId, partNumber);
            if (url.isEmpty()) {
                lastError = "Upload-URL nicht verfügbar";
            } else {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url.get()))
                        .timeout(PART_REQUEST_TIMEOUT)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(buffer, 0, length))
                        .build();
                    HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() / 100 == 2) {
                        Optional<String> etag = response.headers().firstValue("ETag");
                        if (etag.isPresent()) {
                            return etag.get();
                        }
                        lastError = "ETag fehlt in der Antwort";
                    } else {
                        lastError = "HTTP " + response.statusCode();
                    }
                } catch (IOException ex) {
                    lastError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new BackupCancelledException();
                }
            }

            if (attempt < retries) {
                pause(Math.min(30_000L, 2_000L * attempt));
            }
        }
        throw new IOException("Teil " + partNumber + " konnte nicht hochgeladen werden: " + lastError);
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BackupCancelledException();
        }
    }
}
