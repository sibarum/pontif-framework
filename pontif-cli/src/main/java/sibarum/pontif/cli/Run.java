package sibarum.pontif.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code pontif run <target>} — execute a single {@code .ptf} file, a project
 * directory (one holding a {@code module.toml} marker, or a sole module
 * with a {@code main}), or a packaged {@code .ptfpkg} artifact.
 *
 * <p>Each target shape maps onto an existing compiler entry point:
 * a file → {@code compileAlt} (sibling {@code requires} resolved from the
 * file's directory); a directory → {@code compileProjectDir}; a {@code .ptfpkg}
 * → unpack then {@code compileProjectDir} ({@link Artifacts}).
 */
@Command(name = "run",
        mixinStandardHelpOptions = true,
        description = "Run a .ptf file, a project directory, or a .ptfpkg artifact.")
public final class Run implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<target>",
            description = "A .ptf file, a project directory, or a .ptfpkg artifact.")
    Path target;

    @Override
    public Integer call() throws Exception {
        if (!Files.exists(target)) {
            System.err.println("No such file or directory: " + target);
            return 2;
        }

        if (Files.isDirectory(target)) {
            return CliSupport.runAndReport(CliSupport.COMPILER.compileProjectDir(target));
        }

        String fileName = target.getFileName().toString();
        if (fileName.endsWith(Artifacts.EXTENSION)) {
            return Artifacts.run(target);
        }

        String source = Files.readString(target);
        Path resolveDir = target.toAbsolutePath().getParent();
        CompileResult compiled = CliSupport.COMPILER.compileAlt(source, fileName, resolveDir);
        return CliSupport.runAndReport(compiled);
    }
}
