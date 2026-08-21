package sibarum.pontif.gui;

import sibarum.pontif.net.debug.DebugSession;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;

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
        // pontif.gui / pontif.plot / pontif.net / pontif.shape and any other extension on the
        // classpath self-register via ServiceLoader discovery (BuiltinModules → installDiscovered),
        // which runs before the compile below resolves any module — no per-extension wiring here.

        Path target = Path.of(args[0]);
        Path resolveDir = args.length > 1 && !args[1].isBlank() ? Path.of(args[1]) : null;
        String source = Files.readString(target);
        String displayName = args.length > 2 && !args[2].isBlank()
                ? args[2] : target.getFileName().toString();

        // Attach the editor's debug port if it asked for one (via PONTIF_DEBUG_PORT); the tap then
        // mirrors this program's emit fan-out back to the editor. No-op when run outside the editor.
        DebugSession debug = DebugSession.attachFromEnv(displayName);

        PontifCompiler compiler = new PontifCompiler();
        PontifRunner runner = new PontifRunner();

        PontifRunner.RunResult result = runner.run(
                compiler.compile(source, displayName, resolveDir),
                PontifRunner.Engine.INTERPRETER);

        if (result.isError()) {
            if (debug != null) {
                debug.runFailed(result.text(), 0, 0);
            }
            System.err.println(result.text());
            System.exit(1);
        }
        // Success: the window has opened and been closed. The window call's result is the inert
        // for-effect placeholder (rendered as no output), so there is nothing to print.
        if (debug != null) {
            debug.runCompleted(result.text());
        }
    }
}
