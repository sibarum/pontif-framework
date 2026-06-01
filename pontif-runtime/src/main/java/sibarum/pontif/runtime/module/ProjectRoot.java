package sibarum.pontif.runtime.module;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A Pontif project root: the directory holding the {@code module.ptf.toml}
 * marker (echoing SPN's {@code module.spn}). The marker's presence marks the
 * root; its one optional hand-parsed line, {@code entry = "a.b"}, names the
 * module whose {@code main} runs. No TOML dependency — anything past the single
 * {@code entry} key is ignored for now.
 */
public record ProjectRoot(Path rootDir, Optional<String> entryModule) {

    public static final String MARKER = "module.ptf.toml";

    /**
     * Reads the project root at {@code rootDir}, parsing the optional
     * {@code entry} line from the marker if present. A missing marker yields a
     * root with no declared entry (entry is then inferred from the single
     * module that has a {@code main}).
     */
    public static ProjectRoot read(Path rootDir) throws IOException {
        Path marker = rootDir.resolve(MARKER);
        Optional<String> entry = Optional.empty();
        if (Files.isRegularFile(marker)) {
            for (String line : Files.readAllLines(marker)) {
                String t = line.trim();
                if (t.startsWith("#") || !t.startsWith("entry")) continue;
                int eq = t.indexOf('=');
                if (eq < 0) continue;
                String value = t.substring(eq + 1).trim();
                // strip surrounding quotes if present: entry = "app"
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!value.isEmpty()) entry = Optional.of(value);
            }
        }
        return new ProjectRoot(rootDir, entry);
    }
}
