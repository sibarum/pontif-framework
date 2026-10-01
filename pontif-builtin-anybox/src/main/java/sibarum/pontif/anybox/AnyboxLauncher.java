package sibarum.pontif.anybox;

import dev.vexelray.framework.shell.AppInfo;
import dev.vexelray.framework.shell.VexelApplication;
import sibarum.pontif.host.PontifWiring;
import sibarum.pontif.runtime.PontifCompiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Runs an Anybox Pontif program as a vexelray-framework application: the program's conductors are the
 * framework's components, its {@code window} mounts into the framework's {@code Gui}, and the loop, the input,
 * the clipboard, window memory and the automation socket are the framework's (docs/anybox.md).
 *
 * <p>Nothing registers the extension here: {@code pontif.gui} self-registers via ServiceLoader discovery,
 * which runs before the compile below resolves any module.
 *
 * <p>Args: {@code <program.ptf> [resolveDir] [displayName] [--framework-flags...]} - the middle two for an
 * editor running an unsaved buffer from a temp file, so sibling {@code requires} modules still resolve and
 * errors still name the real source. Everything from the first {@code --} on is the framework's own command
 * line ({@code --automation}, ...).
 */
public final class AnyboxLauncher {

    private static final int WIDTH = 900;
    private static final int HEIGHT = 600;

    public static void main(String[] args) throws Exception {
        int positional = 0;
        while (positional < args.length && !args[positional].startsWith("--")) positional++;
        if (positional < 1) {
            System.err.println("usage: AnyboxLauncher <program.ptf> [resolveDir] [displayName] [--framework-flags...]");
            System.exit(2);
            return;
        }
        Path target = Path.of(args[0]);
        Path resolveDir = positional > 1 && !args[1].isBlank() ? Path.of(args[1]) : null;
        String source = Files.readString(target);
        String displayName = positional > 2 && !args[2].isBlank()
                ? args[2] : target.getFileName().toString();
        String[] frameworkArgs = Arrays.copyOfRange(args, positional, args.length);

        PontifCompiler.CompileResult result = new PontifCompiler().compile(source, displayName, resolveDir);
        if (!(result instanceof PontifCompiler.CompileResult.Compiled compiled)) {
            System.err.println(((PontifCompiler.CompileResult.Failed) result).error().text());
            System.exit(1);
            return;
        }

        VexelApplication.run(
                new PontifWiring(appInfo(displayName), compiled.program(),
                        shell -> Mounts.install(new FrameworkWindowHost(shell))),
                frameworkArgs);
    }

    /** Facts per program: the name is the settings directory, so each program remembers its own window. */
    static AppInfo appInfo(String displayName) {
        String stem = displayName.replaceFirst("\\.[^.]*$", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return new AppInfo("pontif-" + (stem.isEmpty() ? "program" : stem), displayName, WIDTH, HEIGHT);
    }
}
