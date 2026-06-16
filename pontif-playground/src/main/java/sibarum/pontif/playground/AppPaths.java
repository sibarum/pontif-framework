package sibarum.pontif.playground;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves (and lazily creates) the playground's per-user state directory,
 * {@code ~/.pontif-playground/} — home to the session file and the
 * compressed recovery copies. All other persistence classes route their
 * paths through here so the on-disk layout lives in one place.
 */
final class AppPaths {

    private AppPaths() {}

    static final String DIR_NAME = ".pontif-playground";

    static Path baseDir() {
        return Path.of(System.getProperty("user.home"), DIR_NAME);
    }

    /** Flat {@code key=value} snapshot of the last session (open file, window geometry). */
    static Path sessionFile() {
        return baseDir().resolve("session");
    }

    /** Directory holding the GZIP recovery copies, one per edited document. */
    static Path recoveryDir() {
        return baseDir().resolve("recovery");
    }

    /**
     * Create {@link #baseDir} and {@link #recoveryDir} if absent. Returns
     * {@code false} (a no-op otherwise) when the directories can't be created
     * — e.g. a locked-down home dir — so callers disable persistence rather
     * than crash the editor.
     */
    static boolean ensureDirs() {
        try {
            Files.createDirectories(recoveryDir()); // also creates baseDir()
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
