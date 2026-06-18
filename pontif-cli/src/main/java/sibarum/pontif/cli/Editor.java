package sibarum.pontif.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code pontif editor [file]} — open the Pontif Editor GUI, optionally on a
 * file. The GUI is a native-windowed JVM app, so this launches it as a detached
 * subprocess ({@link EditorLauncher}) and returns to the prompt; the editor
 * keeps running on its own.
 */
@Command(name = "editor",
        mixinStandardHelpOptions = true,
        description = "Open the Pontif Editor GUI (optionally on a .ptf file).")
public final class Editor implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<file>",
            description = "A .ptf file to open in the editor.")
    Path file;

    @Option(names = "--print", description = "Print the launch command and exit, without opening the editor.")
    boolean print;

    @Override
    public Integer call() throws Exception {
        if (file != null && !Files.exists(file)) {
            System.err.println("No such file: " + file);
            return 2;
        }

        Path jar = EditorLauncher.resolveJar();
        if (jar == null) {
            System.err.println("Could not locate the editor jar (" + EditorLauncher.JAR_NAME + ").");
            System.err.println("Build it with `mvn -pl pontif-playground -am package`, "
                    + "or set PONTIF_EDITOR_JAR to its path.");
            return 1;
        }

        List<String> command = EditorLauncher.buildCommand(jar, file);
        if (print) {
            System.out.println(String.join(" ", command));
            return 0;
        }

        Process process = new ProcessBuilder(command).inheritIO().start();
        // Don't block on the GUI: detach and return. (Give it a brief moment to
        // surface an immediate launch failure — a bad classpath or missing
        // native — rather than silently reporting success.)
        if (process.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            int exit = process.exitValue();
            if (exit != 0) {
                System.err.println("Editor exited immediately with code " + exit + ".");
                return exit;
            }
            return 0;   // exited cleanly (e.g. closed at once)
        }
        System.out.println("Pontif Editor launched" + (file != null ? " on " + file : "") + ".");
        return 0;
    }
}
