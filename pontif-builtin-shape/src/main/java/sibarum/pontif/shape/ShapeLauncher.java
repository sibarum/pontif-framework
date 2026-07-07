package sibarum.pontif.shape;

import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs a shape Pontif program (docs/shapes.md). Extensions on the classpath ({@code pontif.shape}
 * and the {@code pontif.plot} render path it reuses) self-register via ServiceLoader discovery, so
 * this just compiles and runs the given {@code .ptf} <b>on the main thread</b> — so a
 * {@code render(...)} / {@code previewGradientField(...)} call's blocking GLFW loop owns the root
 * thread, satisfying GLFW's thread affinity (mirrors {@code GuiLauncher}).
 *
 * <p>Args: {@code <program.ptf> [resolveDir] [displayName]} — {@code resolveDir} is where sibling
 * {@code requires} modules live (the editor passes the original file's directory when running an
 * unsaved buffer), {@code displayName} is the source name shown in errors. Both optional.
 */
public final class ShapeLauncher {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: ShapeLauncher <program.ptf> [resolveDir] [displayName]");
            System.exit(2);
            return;
        }
        // pontif.shape (and the pontif.plot path it reuses) self-register via ServiceLoader
        // discovery (BuiltinModules → installDiscovered) before the compile below — no wiring here.

        Path target = Path.of(args[0]);
        Path resolveDir = args.length > 1 && !args[1].isBlank() ? Path.of(args[1]) : null;
        String source = Files.readString(target);
        String displayName = args.length > 2 && !args[2].isBlank()
                ? args[2] : target.getFileName().toString();

        PontifRunner.RunResult result = new PontifRunner().run(
                new PontifCompiler().compileAlt(source, displayName, resolveDir),
                PontifRunner.Engine.INTERPRETER);

        if (result.isError()) {
            System.err.println(result.text());
            System.exit(1);
        }
        // Success: the shape window has opened and been closed; the render/previewGradientField call's
        // result is the inert for-effect placeholder, so there is nothing to print.
    }
}
