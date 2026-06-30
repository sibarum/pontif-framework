package sibarum.pontif.gui;

import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;
import sibarum.pontif.runtime.module.Extensions;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs a GUI Pontif program (docs/extensions.md). Installs the {@link GuiExtension} (the builtin
 * IO extension is already installed by default), then compiles and runs the given {@code .ptf}
 * <b>on the main thread</b> — so a {@code window(...)} call's blocking GLFW loop owns the root
 * thread, satisfying GLFW's thread affinity. This is the dasum-bearing entry point, kept out of
 * the lean CLI so the CLI/native-image stay GUI-free.
 *
 * <p>Args: {@code <program.ptf> [resolveDir] [displayName]}. The editor runs an unsaved buffer
 * from a temp file, so it passes the ORIGINAL file's directory as {@code resolveDir} (where
 * sibling {@code requires} modules live — the temp dir has none) and the real source name as
 * {@code displayName} (so errors don't point at the temp file). Both optional.
 */
public final class GuiLauncher {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: GuiLauncher <program.ptf> [resolveDir] [displayName]");
            System.exit(2);
            return;
        }
        Extensions.install(new GuiExtension());
        Extensions.install(new PlotExtension());

        Path target = Path.of(args[0]);
        Path resolveDir = args.length > 1 && !args[1].isBlank() ? Path.of(args[1]) : null;
        String source = Files.readString(target);
        String displayName = args.length > 2 && !args[2].isBlank()
                ? args[2] : target.getFileName().toString();
        PontifCompiler compiler = new PontifCompiler();
        PontifRunner runner = new PontifRunner();

        PontifRunner.RunResult result = runner.run(
                compiler.compileAlt(source, displayName, resolveDir),
                PontifRunner.Engine.INTERPRETER);

        if (result.isError()) {
            System.err.println(result.text());
            System.exit(1);
        }
        // Success: the window has opened and been closed. The window call's result is the inert
        // for-effect placeholder (rendered as no output), so there is nothing to print.
    }
}
