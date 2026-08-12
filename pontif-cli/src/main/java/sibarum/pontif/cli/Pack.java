package sibarum.pontif.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code pontif pack [dir]} — package a project into a compressed
 * {@code .ptfpkg} source bundle.
 *
 * <p>The project is first <b>validated by compiling it</b>
 * ({@code compileProjectDir}), so a project that doesn't compile — or whose
 * proof gates reject it — never produces an artifact. Only then are the
 * {@code module.toml} marker and every {@code .ptf} source zipped (paths
 * preserved relative to the root). Symmetric with {@link Artifacts#run}.
 */
@Command(name = "pack",
        mixinStandardHelpOptions = true,
        description = "Package a project's sources into a compressed .ptfpkg artifact.")
public final class Pack implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<dir>", arity = "0..1", defaultValue = ".",
            description = "Project directory to package (default: current directory).")
    Path dir;

    @Option(names = {"-o", "--output"}, paramLabel = "<file>",
            description = "Output artifact path (default: <name>-<version>.ptfpkg in the current directory).")
    Path output;

    @Override
    public Integer call() throws Exception {
        if (!Files.isDirectory(dir)) {
            System.err.println("Not a directory: " + dir);
            return 2;
        }

        // Validate by compiling — a broken project must never pack.
        CompileResult result = CliSupport.COMPILER.compileProjectDir(dir);
        if (result instanceof CompileResult.Failed f) {
            System.err.println("Refusing to pack — project does not compile:");
            System.err.println("  " + f.error().text());
            return 1;
        }

        List<Path> sources = collectSources(dir);
        if (sources.isEmpty()) {
            System.err.println("No files to package under " + dir);
            return 1;
        }

        PackageManifest manifest = PackageManifest.read(dir);
        Path out = (output != null)
                ? output
                : Path.of(manifest.artifactBaseName() + Artifacts.EXTENSION);

        Artifacts.writeZip(out, dir, sources);
        long modules = sources.stream()
                .filter(p -> p.getFileName().toString().endsWith(".ptf")).count();
        System.out.println("Packaged " + modules + " module"
                + (modules == 1 ? "" : "s") + " -> " + out);
        return 0;
    }

    /** The marker (if present) plus every {@code .ptf} under {@code dir}, sorted. */
    private static List<Path> collectSources(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        Path marker = dir.resolve(PackageManifest.MARKER);
        if (Files.isRegularFile(marker)) files.add(marker);
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".ptf"))
                    .sorted()
                    .forEach(files::add);
        }
        return files;
    }
}
