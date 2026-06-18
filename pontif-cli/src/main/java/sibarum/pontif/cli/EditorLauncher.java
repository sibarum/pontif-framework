package sibarum.pontif.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Locates the Pontif Editor's runnable jar and builds the {@code java} command
 * that launches it. The editor is a GLFW/native GUI, so it stays on a JVM
 * subprocess rather than being folded into the (headless) native CLI image —
 * {@code pontif editor} shells out here.
 *
 * <p>Resolution order for the jar: the {@code PONTIF_EDITOR_JAR} override, then
 * locations next to the running CLI (an install's {@code lib/}, or — in the dev
 * tree — the sibling {@code pontif-playground/target}), then the working
 * directory. {@code java} is taken from {@code java.home} (the JVM running the
 * jar), then {@code JAVA_HOME}, then the {@code PATH}.
 */
final class EditorLauncher {

    static final String JAR_NAME = "pontif-editor.jar";

    private EditorLauncher() {}

    /** The editor jar if one can be found, else {@code null}. */
    static Path resolveJar() {
        String override = System.getenv("PONTIF_EDITOR_JAR");
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            return Files.isRegularFile(p) ? p : null;
        }
        for (Path candidate : candidates()) {
            if (candidate != null && Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static List<Path> candidates() {
        List<Path> out = new ArrayList<>();
        Path self = selfDir();
        if (self != null) {
            out.add(self.resolve(JAR_NAME));                       // alongside the binary/jar
            out.add(self.resolve("lib").resolve(JAR_NAME));        // install layout: <home>/lib
            // dev tree: <repo>/pontif-cli/target → <repo>/pontif-playground/target
            out.add(self.resolve("../../pontif-playground/target").resolve(JAR_NAME).normalize());
        }
        Path cwd = Path.of("").toAbsolutePath();
        out.add(cwd.resolve("pontif-playground/target").resolve(JAR_NAME));
        out.add(cwd.resolve(JAR_NAME));
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

    /** The {@code java} launcher to use. */
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

    /** The full launch command for {@code jar}, opening {@code file} if non-null. */
    static List<String> buildCommand(Path jar, Path file) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable());
        cmd.add("--enable-native-access=ALL-UNNAMED");   // dasum-glfw native bindings
        cmd.add("-jar");
        cmd.add(jar.toString());
        if (file != null) cmd.add(file.toString());
        return cmd;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
