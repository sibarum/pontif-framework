package sibarum.pontif.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Locates the Pontif Editor and builds the command that launches it.
 *
 * <p>Two forms of the editor exist, and the launcher prefers the leaner one:
 * <ol>
 *   <li>a standalone <b>native binary</b> ({@code pontif-editor.exe}) — launched
 *       directly, no JVM needed;</li>
 *   <li>the <b>runnable jar</b> ({@code pontif-editor.jar}) — launched with
 *       {@code java --enable-native-access=ALL-UNNAMED -jar …}.</li>
 * </ol>
 * For each, resolution order is: an explicit env override
 * ({@code PONTIF_EDITOR_EXE} / {@code PONTIF_EDITOR_JAR}), then locations next to
 * the running CLI (an install's {@code lib/}, or — in the dev tree — the sibling
 * {@code pontif-playground/target}), then the working directory. {@code java}
 * (jar form only) comes from {@code java.home}, then {@code JAVA_HOME}, then the
 * {@code PATH}.
 */
final class EditorLauncher {

    static final String JAR_NAME = "pontif-editor.jar";
    static final String EXE_NAME = isWindows() ? "pontif-editor.exe" : "pontif-editor";

    private EditorLauncher() {}

    /** Where the editor was found and in what form. */
    record Target(Path path, boolean isNative) {}

    /** The editor to launch — native binary preferred, else jar — or {@code null}. */
    static Target resolve() {
        Path exe = resolveBy("PONTIF_EDITOR_EXE", EXE_NAME);
        if (exe != null) return new Target(exe, true);
        Path jar = resolveBy("PONTIF_EDITOR_JAR", JAR_NAME);
        if (jar != null) return new Target(jar, false);
        return null;
    }

    /** First existing path for {@code fileName}, honoring {@code envVar} first. */
    private static Path resolveBy(String envVar, String fileName) {
        String override = System.getenv(envVar);
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            return Files.isRegularFile(p) ? p : null;
        }
        for (Path candidate : candidates(fileName)) {
            if (candidate != null && Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static List<Path> candidates(String fileName) {
        List<Path> out = new ArrayList<>();
        Path self = selfDir();
        if (self != null) {
            out.add(self.resolve(fileName));                       // alongside the binary/jar
            out.add(self.resolve("lib").resolve(fileName));        // install layout: <home>/lib
            // dev tree: <repo>/pontif-cli/target → <repo>/pontif-playground/target
            out.add(self.resolve("../../pontif-playground/target").resolve(fileName).normalize());
        }
        Path cwd = Path.of("").toAbsolutePath();
        out.add(cwd.resolve("pontif-playground/target").resolve(fileName));
        out.add(cwd.resolve(fileName));
        return out;
    }

    /** Directory containing the running CLI jar (or class tree), or null. */
    private static Path selfDir() {
        try {
            var source = EditorLauncher.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            Path loc = Path.of(source.getLocation().toURI());
            return Files.isRegularFile(loc) ? loc.getParent() : loc;
        } catch (Exception e) {
            return null;   // native image (no code source) → fall back to cwd candidates
        }
    }

    /** The {@code java} launcher to use for the jar form. */
    static String javaExecutable() {
        String exe = isWindows() ? "java.exe" : "java";
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            Path bin = Path.of(javaHome, "bin", exe);
            if (Files.isRegularFile(bin)) return bin.toString();
        }
        String envHome = System.getenv("JAVA_HOME");
        if (envHome != null && !envHome.isBlank()) {
            Path bin = Path.of(envHome, "bin", exe);
            if (Files.isRegularFile(bin)) return bin.toString();
        }
        return "java";   // PATH
    }

    /** The launch command for {@code target}, opening {@code file} if non-null. */
    static List<String> buildCommand(Target target, Path file) {
        List<String> cmd = new ArrayList<>();
        if (target.isNative()) {
            cmd.add(target.path().toString());
        } else {
            cmd.add(javaExecutable());
            cmd.add("--enable-native-access=ALL-UNNAMED");   // dasum-glfw FFM bindings
            cmd.add("-jar");
            cmd.add(target.path().toString());
        }
        if (file != null) cmd.add(file.toString());
        return cmd;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
