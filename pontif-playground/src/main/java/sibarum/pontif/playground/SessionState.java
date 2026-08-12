package sibarum.pontif.playground;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The persisted session snapshot restored on the next launch: which file was
 * open and the window's geometry.
 *
 * <p>Serialized as a flat, human-readable {@code key=value} text file, one
 * entry per line. The value is the raw remainder after the first {@code '='},
 * with no escaping, so Windows paths (with {@code ':'} and {@code '\'}) survive
 * a round-trip untouched. Lines starting with {@code '#'} are comments, and
 * unknown keys are ignored on read — so the format can grow without breaking
 * files written by an older build.
 *
 * <p>Geometry fields use {@code -1} as "unknown / don't restore"; {@code -1}
 * never names a real window size or a sensible position, so it doubles as a
 * clean sentinel.
 */
final class SessionState {

    /** A position/size component meaning "not recorded — don't restore". */
    static final int UNSET = -1;

    String openFile = null;     // absolute path open at save time, or null for untitled
    int windowX = UNSET;
    int windowY = UNSET;
    int windowWidth = UNSET;
    int windowHeight = UNSET;
    boolean maximized = false;
    boolean openMostRecent = false;  // setting: reopen openFile + boot into Editor tab on launch
    boolean autoCreateMarker = true; // setting: scaffold a module.toml when creating a new module (default on)

    boolean hasGeometry() {
        return windowWidth > 0 && windowHeight > 0;
    }

    boolean hasPosition() {
        // Guard against absurd off-screen coordinates from a since-removed monitor.
        return windowX != UNSET && windowY != UNSET
                && windowX > -30000 && windowY > -30000
                && windowX < 30000 && windowY < 30000;
    }

    static Optional<SessionState> read(Path file) {
        if (!Files.isReadable(file)) return Optional.empty();
        Map<String, String> kv = new HashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                kv.put(line.substring(0, eq).trim(), line.substring(eq + 1));
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        SessionState s = new SessionState();
        String open = kv.get("open");
        s.openFile = (open == null || open.isBlank()) ? null : open;
        s.windowX = parseInt(kv.get("window.x"));
        s.windowY = parseInt(kv.get("window.y"));
        s.windowWidth = parseInt(kv.get("window.width"));
        s.windowHeight = parseInt(kv.get("window.height"));
        s.maximized = Boolean.parseBoolean(kv.get("window.maximized"));
        s.openMostRecent = Boolean.parseBoolean(kv.get("open.mostRecent"));
        // Absent key means "never written" (older build or fresh session) — keep the
        // default-on value rather than letting parseBoolean(null) force it off.
        s.autoCreateMarker = kv.containsKey("module.autoCreate")
                ? Boolean.parseBoolean(kv.get("module.autoCreate"))
                : true;
        return Optional.of(s);
    }

    /** Serialize to {@code file}. Best-effort — a failed write just loses one snapshot. */
    void write(Path file) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Pontif Playground session — restored on next launch.\n");
        if (openFile != null) sb.append("open=").append(openFile).append('\n');
        sb.append("window.x=").append(windowX).append('\n');
        sb.append("window.y=").append(windowY).append('\n');
        sb.append("window.width=").append(windowWidth).append('\n');
        sb.append("window.height=").append(windowHeight).append('\n');
        sb.append("window.maximized=").append(maximized).append('\n');
        sb.append("open.mostRecent=").append(openMostRecent).append('\n');
        sb.append("module.autoCreate=").append(autoCreateMarker).append('\n');
        try {
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // best-effort
        }
    }

    /** A stable serialized form used to skip rewriting an unchanged session. */
    String fingerprint() {
        return openFile + "|" + windowX + "|" + windowY + "|"
                + windowWidth + "|" + windowHeight + "|" + maximized + "|" + openMostRecent
                + "|" + autoCreateMarker;
    }

    private static int parseInt(String v) {
        if (v == null) return UNSET;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return UNSET;
        }
    }
}
