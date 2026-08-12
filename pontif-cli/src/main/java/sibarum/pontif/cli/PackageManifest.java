package sibarum.pontif.cli;

import sibarum.pontif.runtime.module.ProjectRoot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The hand-parsed contents of a project's {@code module.toml} marker, for
 * the CLI's purposes: {@code name}, {@code namespace}, {@code version}, and
 * {@code entry}. Deliberately dependency-free — the same single-pass
 * {@code key = "value"} reader the runtime's {@code ProjectRoot} uses, just
 * over a few more keys. Anything else in the file is ignored.
 *
 * <p>{@code ProjectRoot} remains the authority on {@code entry} for the compile
 * path; this record exists so {@link Pack} can name the artifact
 * ({@code name-version.ptfpkg}) and {@link New} can write a consistent marker.
 */
record PackageManifest(
        Optional<String> name,
        Optional<String> namespace,
        Optional<String> version,
        Optional<String> entry) {

    /** The project-root marker filename — single source of truth in {@link ProjectRoot#MARKER}. */
    static final String MARKER = ProjectRoot.MARKER;

    static PackageManifest read(Path rootDir) throws IOException {
        Path marker = rootDir.resolve(MARKER);
        Map<String, String> kv = new HashMap<>();
        if (Files.isRegularFile(marker)) {
            for (String raw : Files.readAllLines(marker)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                if (val.length() >= 2 && val.startsWith("\"") && val.endsWith("\"")) {
                    val = val.substring(1, val.length() - 1);
                }
                if (!key.isEmpty() && !val.isEmpty()) kv.put(key, val);
            }
        }
        return new PackageManifest(
                Optional.ofNullable(kv.get("name")),
                Optional.ofNullable(kv.get("namespace")),
                Optional.ofNullable(kv.get("version")),
                Optional.ofNullable(kv.get("entry")));
    }

    /** Artifact filename stem, {@code name-version}, with sane fallbacks. */
    String artifactBaseName() {
        return name.orElse("package") + "-" + version.orElse("0.0.0");
    }
}
