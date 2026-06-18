package sibarum.pontif.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code pontif new <namespace>} — scaffold a fresh project: a directory with a
 * {@code module.ptf.toml} marker (name / namespace / version / entry) and one
 * sample {@code .ptf} module that declares the namespace and ends in a runnable
 * {@code main}. The result runs as-is ({@code pontif run <dir>}).
 *
 * <p>Refuses to write into a non-empty directory, so it never clobbers existing
 * work.
 */
@Command(name = "new",
        mixinStandardHelpOptions = true,
        description = "Scaffold a new Pontif project (marker + sample source).")
public final class New implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<namespace>",
            description = "Dotted project namespace, e.g. my.app.")
    String namespace;

    @Option(names = {"-d", "--dir"},
            description = "Target directory (default: the namespace itself).")
    Path dir;

    @Option(names = "--version", paramLabel = "<ver>", defaultValue = "0.1.0",
            description = "Initial project version (default: ${DEFAULT-VALUE}).")
    String version;

    @Override
    public Integer call() throws Exception {
        if (namespace.isBlank()) {
            System.err.println("A namespace is required, e.g. `pontif new my.app`.");
            return 2;
        }
        String shortName = namespace.contains(".")
                ? namespace.substring(namespace.lastIndexOf('.') + 1)
                : namespace;
        Path target = (dir != null) ? dir : Path.of(namespace);

        if (Files.exists(target) && isNonEmptyDir(target)) {
            System.err.println("Refusing to scaffold into a non-empty directory: " + target);
            return 1;
        }
        Files.createDirectories(target);

        Path marker = target.resolve(PackageManifest.MARKER);
        Files.writeString(marker, """
                name = "%s"
                namespace = "%s"
                version = "%s"
                entry = "%s"
                """.formatted(shortName, namespace, version, namespace));

        Path source = target.resolve(shortName + ".ptf");
        Files.writeString(source, """
                module %s

                function greet(n:Int):Int -> n + 1

                greet(41)
                """.formatted(namespace));

        System.out.println("Created project '" + namespace + "' in " + target);
        System.out.println("  " + target.relativize(marker));
        System.out.println("  " + target.relativize(source));
        System.out.println("Run it with:  pontif run " + target);
        return 0;
    }

    private static boolean isNonEmptyDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return true;  // a non-dir at the path also blocks
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isPresent();
        }
    }
}
