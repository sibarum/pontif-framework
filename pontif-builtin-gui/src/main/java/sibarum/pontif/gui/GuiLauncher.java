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
 */
public final class GuiLauncher {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: GuiLauncher <program.ptf>");
            System.exit(2);
            return;
        }
        Extensions.install(new GuiExtension());

        Path target = Path.of(args[0]);
        String source = Files.readString(target);
        PontifCompiler compiler = new PontifCompiler();
        PontifRunner runner = new PontifRunner();

        PontifRunner.RunResult result = runner.run(
                compiler.compileAlt(source, target.getFileName().toString()),
                PontifRunner.Engine.INTERPRETER);

        if (result.isError()) {
            System.err.println(result.text());
            System.exit(1);
        }
        // Success: the window has opened and been closed. The window call's result is the inert
        // for-effect placeholder (rendered as no output), so there is nothing to print.
    }
}
