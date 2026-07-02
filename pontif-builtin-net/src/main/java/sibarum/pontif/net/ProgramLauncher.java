package sibarum.pontif.net;

import sibarum.pontif.net.debug.DebugSession;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs a non-GUI Pontif program in its own JVM, streaming telemetry back to the editor over the
 * debug port. This is the headless counterpart to {@code sibarum.pontif.gui.GuiLauncher}: same
 * contract, but it installs no windowed extension and runs on an ordinary thread (there is no GLFW
 * root-thread affinity to honor). The editor spawns it for every ordinary {@code Run} so a program
 * crash (uncaught exception, {@code System.exit}, OOM) is isolated from the editor and witnessed by
 * the process exit code, and so all of its stdout/stderr flows to the editor's event log.
 *
 * <p>Args: {@code <program.ptf> [resolveDir] [displayName]} — mirrors {@code GuiLauncher}. The
 * editor runs an unsaved buffer from a temp file, so it passes the ORIGINAL file's directory as
 * {@code resolveDir} (where sibling {@code requires} modules live — the temp dir has none) and the
 * real source name as {@code displayName} (so errors don't point at the temp file). Both optional.
 *
 * <p>Reporting is split by whether the editor attached a debug port: when tapped, the run result
 * and failures travel as typed telemetry ({@link DebugSession#runCompleted}/{@link
 * DebugSession#runFailed}); when untapped (run outside the editor, or the port could not open), the
 * result/error is printed to stdout/stderr instead so it is never lost. Either way a failure exits
 * non-zero, which is the editor's crash backstop.
 */
public final class ProgramLauncher {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: ProgramLauncher <program.ptf> [resolveDir] [displayName]");
            System.exit(2);
            return;
        }
        // The builtin IO extension is installed by default; Net makes `requires pontif.net`
        // resolvable. No GUI/Plot extension — a program that needs a window is routed to
        // GuiLauncher by the editor, not here.
        sibarum.pontif.runtime.module.Extensions.install(new NetExtension());

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
                compiler.compileAlt(source, displayName, resolveDir),
                PontifRunner.Engine.INTERPRETER);

        if (result.isError()) {
            if (debug != null) {
                debug.runFailed(result.text(), 0, 0);
            } else {
                System.err.println(result.text());
            }
            System.exit(1);
        }
        if (debug != null) {
            debug.runCompleted(result.text());
        } else {
            // Untapped: stdout is the only channel back, so the result value must be printed
            // (mirrors `pontif run`, which prints RunResult.text() on success).
            System.out.println(result.text());
        }
    }
}
