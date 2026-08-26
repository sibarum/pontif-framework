package sibarum.pontif.playground;

/**
 * The editor's one door to {@code pontif-builtin-gui}, and the only place in Playground that names it.
 *
 * <p><b>Why a door rather than a call.</b> The windowed extension — {@code pontif.gui} / {@code pontif.plot} and
 * their toolkit — is being deleted rather than ported (docs/plotting.md, §The renderer seam). The editor is not:
 * it stays, on its own toolkit, for as long as it is the editor. Those two facts only coexist if the editor treats
 * the windowed extension as <b>a capability it may or may not have</b>, which is what this class makes it.
 *
 * <p>So the references are by name, resolved at runtime. That buys three things at once: the editor compiles with
 * the extension absent, it <em>runs</em> with it absent (the GUI-run action reports that it cannot rather than
 * spawning a JVM that dies of a missing main class), and the deletion is a dependency line in one pom instead of
 * an edit spread through a 2,300-line UI. The dependency is {@code runtime}-scoped for exactly that reason: it
 * must be on the classpath, and nothing may compile against it.
 *
 * <p><b>The cost, stated.</b> A direct call is a reachability anchor for a native image and a reflective one is
 * not, so the {@code -Pnative} editor needs {@link #LAUNCHER} in its reflection configuration to keep the GUI-run
 * path working. That is the trade for the extension being optional, and it is worth it: without it the editor's
 * build is hostage to a module scheduled for removal.
 */
final class OptionalGui {

    /**
     * The windowed program launcher's class name. Used two ways — to ask whether the extension is here at all,
     * and as the child JVM's main class, which was always a string (a GUI program runs out of process so it owns
     * its own window and root thread).
     */
    static final String LAUNCHER = "sibarum.pontif.anybox.AnyboxLauncher";

    /** The extension itself, whose {@code pontifSource()} is what makes {@code pontif.gui} names navigable. */
    private static final String EXTENSION = "sibarum.pontif.anybox.AnyboxExtension";

    private OptionalGui() {
    }

    /** Whether the windowed extension is on the classpath at all. */
    static boolean present() {
        return load(LAUNCHER) != null;
    }

    /**
     * Run the windowed launcher in <em>this</em> JVM with {@code args} — the flag path
     * ({@code --run-gui}), which is how a native-image editor re-enters itself as a GUI program's host rather
     * than spawning a JVM. Reports and returns if the extension is absent; there is nothing else it could do,
     * and this branch is dormant on the JVM anyway.
     */
    static void runLauncher(String[] args) {
        Class<?> launcher = load(LAUNCHER);
        if (launcher == null) {
            System.err.println("no windowed extension on the classpath: " + LAUNCHER + " not found. "
                    + "GUI programs need pontif-builtin-anybox to run.");
            return;
        }
        try {
            launcher.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (ReflectiveOperationException e) {
            // The class is here but does not answer to main(String[]) — a wiring error, not a missing feature,
            // so it is worth the stack rather than a tidy message.
            throw new IllegalStateException(LAUNCHER + " is present but not runnable", e);
        }
    }

    /**
     * The {@code pontif.gui} module's Pontif source, or null when the extension is absent.
     *
     * <p>The editor never installs the windowed extension globally — GUI programs run in a subprocess — so the
     * in-process compiler has no {@code pontif.gui} to resolve against. Parsing its declared source is what keeps
     * those names navigable without changing the Run path (see {@code DefinitionNavigator}).
     */
    static String moduleSource() {
        Class<?> extension = load(EXTENSION);
        if (extension == null) {
            return null;
        }
        try {
            Object instance = extension.getDeclaredConstructor().newInstance();
            return (String) extension.getMethod("pontifSource").invoke(instance);
        } catch (ReflectiveOperationException e) {
            return null;   // present but unusable: navigation loses one module, which is not worth a failure
        }
    }

    /**
     * {@code name}, or null if it is not on the classpath.
     *
     * <p>{@link LinkageError} is caught as well as {@link ClassNotFoundException}, because "absent" has two
     * shapes: the class is missing, or it is here and its own dependencies are not. The second is what a
     * half-removed extension looks like, and it arrives as an {@code Error} that a
     * {@code catch (Exception)} — the shape this code used to have — walks straight past.
     */
    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
