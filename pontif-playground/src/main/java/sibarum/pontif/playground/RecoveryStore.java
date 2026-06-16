package sibarum.pontif.playground;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Manages GZIP-compressed recovery copies of <em>unsaved</em> editor content
 * under {@code ~/.pontif-playground/recovery/}. One copy per edited document,
 * keyed by the document's identity — a file's absolute path, or a sentinel
 * for the untitled scratch buffer.
 *
 * <p>The contract that makes recovery work without any clean-shutdown bookkeeping:
 * a copy is written while edits differ from what's on disk and deleted the moment
 * the document is saved. So a copy that survives into the next launch means the
 * app exited with unsaved work — exactly what we offer to recover.
 *
 * <p>All operations are best-effort: recovery must never be able to crash the
 * editor, so IO failures are swallowed (a failed write just means no recovery
 * point, a failed read means nothing to offer).
 */
final class RecoveryStore {

    /** Document key for the untitled buffer (no backing file). */
    static final String UNTITLED_KEY = "(untitled)";

    private static final String SUFFIX = ".gz";

    private final Path dir;

    RecoveryStore(Path recoveryDir) {
        this.dir = recoveryDir;
    }

    /** Stable document key for a file (absolute, normalized path) or the untitled buffer. */
    static String keyFor(Path file) {
        return file == null ? UNTITLED_KEY : file.toAbsolutePath().normalize().toString();
    }

    /**
     * Recovery file path for a key. The name pairs a human-scannable basename
     * with a short hash of the full key, so two same-named files in different
     * directories never collide while the directory stays browsable.
     */
    private Path fileFor(String key) {
        String base = key.equals(UNTITLED_KEY)
                ? "untitled"
                : Path.of(key).getFileName().toString();
        return dir.resolve(sanitize(base) + "__" + shortHash(key) + SUFFIX);
    }

    boolean hasRecovery(String key) {
        return Files.isReadable(fileFor(key));
    }

    /** Write (or overwrite) the recovery copy for {@code key}. Best-effort. */
    void write(String key, String content) {
        try {
            Files.write(fileFor(key), gzip(content));
        } catch (IOException e) {
            // best-effort — a missed recovery point isn't worth interrupting editing
        }
    }

    /** The recovered content for {@code key}, or empty if none / unreadable. */
    Optional<String> read(String key) {
        Path f = fileFor(key);
        if (!Files.isReadable(f)) return Optional.empty();
        try {
            return Optional.of(gunzip(Files.readAllBytes(f)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Delete the recovery copy for {@code key} if present. Best-effort. */
    void delete(String key) {
        try {
            Files.deleteIfExists(fileFor(key));
        } catch (IOException e) {
            // best-effort
        }
    }

    /** Delete every recovery file in the directory. Returns the number removed. */
    int purgeAll() {
        if (!Files.isDirectory(dir)) return 0;
        int[] removed = {0};
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                   .forEach(p -> {
                       try {
                           if (Files.deleteIfExists(p)) removed[0]++;
                       } catch (IOException e) {
                           // skip the ones we can't remove
                       }
                   });
        } catch (IOException e) {
            // can't list — report whatever we managed
        }
        return removed[0];
    }

    /** Count of recovery files currently on disk. */
    int count() {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> entries = Files.list(dir)) {
            return (int) entries.filter(p -> p.getFileName().toString().endsWith(SUFFIX)).count();
        } catch (IOException e) {
            return 0;
        }
    }

    // ---------- helpers ----------

    private static byte[] gzip(String s) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static String gunzip(byte[] compressed) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return new String(gz.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** First 8 hex chars of the key's SHA-256 — enough to avoid basename collisions. */
    private static String shortHash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform; this can't happen.
            throw new IllegalStateException(e);
        }
    }

    /** Reduce a basename to filesystem-safe, browsable characters. */
    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
