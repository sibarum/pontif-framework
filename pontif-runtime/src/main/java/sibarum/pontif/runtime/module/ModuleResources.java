package sibarum.pontif.runtime.module;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads a builtin module's Pontif source from the classpath (docs/extensions.md).
 *
 * <p>The convention — the whole point of this class — is <b>zero plumbing</b>: a
 * builtin's source is a {@code .ptf} file whose name <em>is</em> its module name,
 * shipped as a classpath resource under {@code /pontif-modules/}. So a module named
 * {@code pontif.plot} lives at {@code src/main/resources/pontif-modules/pontif.plot.ptf}
 * in whichever jar ships it, and nothing but the module name (declared once, in
 * {@link Extension#moduleName()}) selects it — there is no second filename to keep in
 * sync, no registry to edit, no path to pass. Dropping the file beside the jar's other
 * resources and returning its module name is the entire authoring surface.
 *
 * <p>Resolution is anchored on the ship-ing class so each jar finds its own resources
 * (an external extension's {@code .ptf} rides in its own jar, not pontif-runtime's).
 * A missing or unreadable resource is a <b>build error</b>, not a soft fallback: a
 * builtin whose source is gone is unusable, so we fail loudly at install rather than
 * silently register an empty module.
 */
final class ModuleResources {

    private ModuleResources() {}

    /** The classpath resource path for a module's shipped source. */
    static String resourcePath(String moduleName) {
        return "/pontif-modules/" + moduleName + ".ptf";
    }

    /**
     * Reads {@code moduleName}'s Pontif source, resolved against {@code anchor}'s
     * classloader (so the resource is found in the jar that ships {@code anchor}).
     *
     * @throws IllegalStateException if the resource is absent or cannot be read.
     */
    static String load(Class<?> anchor, String moduleName) {
        String resource = resourcePath(moduleName);
        try (InputStream in = anchor.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(
                        "builtin Pontif module source not found on the classpath: " + resource
                        + " — expected at src/main/resources" + resource
                        + " in the jar that ships " + anchor.getName()
                        + " (module '" + moduleName + "').");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "failed to read builtin Pontif module source " + resource
                    + " (module '" + moduleName + "').", e);
        }
    }
}
