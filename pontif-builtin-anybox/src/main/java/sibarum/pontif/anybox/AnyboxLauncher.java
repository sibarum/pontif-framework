package sibarum.pontif.anybox;

import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs an Anybox Pontif program. Compiles and runs the given {@code .ptf} <b>on the main thread</b>,
 * so a {@code window(...)} call's blocking loop owns the root thread — the window, the swapchain and
 * presentation all live there by the framework's rule, and app logic runs off it.
 *
 * <p>Nothing registers the extension here: {@code pontif.gui} self-registers via ServiceLoader
 * discovery, which runs before the compile below resolves any module.
 *
 * <p>Args: {@code <program.ptf> [resolveDir] [displayName]} — the last two for an editor running an
 * unsaved buffer from a temp file, so sibling {@code requires} modules still resolve and errors
 * still name the real source.
 */
public final class AnyboxLauncher {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: AnyboxLauncher <program.ptf> [resolveDir] [displayName]");
            System.exit(2);
            return;
        }
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
    }
}
