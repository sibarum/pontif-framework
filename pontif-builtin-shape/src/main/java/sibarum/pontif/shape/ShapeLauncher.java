package sibarum.pontif.shape;

import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs a shape Pontif program (docs/shapes.md). Extensions on the classpath self-register via
 * ServiceLoader discovery, so this just compiles and runs the given {@code .ptf} <b>on the main
 * thread</b> — kept that way because a renderer's window loop, if one is present, needs the root
 * thread.
 *
 * <p>{@code pontif.shape} itself draws nothing: {@code raymarch} and {@code gradientField} return
 * views, so a program run here evaluates to a value. Seeing one is a renderer's job.
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
        // pontif.shape self-registers via ServiceLoader discovery (BuiltinModules → installDiscovered)
        // before the compile below — no wiring here, and no renderer required for it to resolve.

        Path target = Path.of(args[0]);
        Path resolveDir = args.length > 1 && !args[1].isBlank() ? Path.of(args[1]) : null;
        String source = Files.readString(target);
        String displayName = args.length > 2 && !args[2].isBlank()
                ? args[2] : target.getFileName().toString();

        PontifRunner.RunResult result = new PontifRunner().run(
                new PontifCompiler().compile(source, displayName, resolveDir),
                PontifRunner.Engine.INTERPRETER);

        if (result.isError()) {
            System.err.println(result.text());
            System.exit(1);
        }
        // A shape program evaluates to a VALUE — a Raymarch or a GradientField, or a plain number from
        // distanceAt — so print it. It used to end in a native that opened a window and returned an
        // inert placeholder, and there was nothing to say; now the result is the whole output.
        System.out.println(result.text());
    }
}
