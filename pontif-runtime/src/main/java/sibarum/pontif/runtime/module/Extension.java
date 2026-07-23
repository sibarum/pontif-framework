package sibarum.pontif.runtime.module;

import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.ir.NativeFunctions;

import java.util.Map;

/**
 * A Pontif <b>language extension</b> (docs/extensions.md) — the single channel through which
 * side-effects enter the otherwise-pure language. An extension is a <b>Pontif-written
 * interface</b> ({@link #pontifSource()}, a module of ordinary-looking declarations) <b>plus
 * associated Java objects</b> that back its native parts, <b>bound by name</b>:
 *
 * <ul>
 *   <li>{@link #effects()} — emit sinks, keyed by the bare event-type name. An
 *       {@code emit StdOut(…)} routes to the matching effect (installed into
 *       {@link NativeFunctions} qualified by {@link #moduleName()}).</li>
 *   <li>{@link #calls()} — application-invoked native functions, keyed by the bare function
 *       name (e.g. {@code stdin}, the GUI {@code window}). The Pontif declaration carries a
 *       placeholder body; the resolved call runs this Java object instead (installed into
 *       {@link NativeCalls}).</li>
 * </ul>
 *
 * <p>The first extension is the builtin {@link IoExtension} (StdOut/StdErr/stdin), installed by
 * default; {@code pontif-builtin-gui} is the first external one. {@link Extensions#install}
 * wires an extension's module into the builtin set and its Java objects into the registries.
 */
public interface Extension {

    /** The module name this extension contributes (e.g. {@code "pontif.events"}, {@code "pontif.gui"}). */
    String moduleName();

    /**
     * The Pontif interface module source — declarations whose native parts this extension backs.
     *
     * <p>By default this is the {@code .ptf} file shipped as a classpath resource under
     * {@code /pontif-modules/<moduleName>.ptf} (see {@link ModuleResources}): an extension author
     * writes that file and need not override this method. The name is derived from
     * {@link #moduleName()}, so there is nothing to keep in sync. Override only for a source that
     * is genuinely synthesized at runtime rather than shipped as a file.
     */
    default String pontifSource() {
        return ModuleResources.load(getClass(), moduleName());
    }

    /** Emit sinks by bare event-type name (qualified with {@link #moduleName()} at install). */
    default Map<String, NativeFunctions.Effect> effects() {
        return Map.of();
    }

    /** Application-invoked native functions by bare function name. */
    default Map<String, NativeCalls.NativeCall> calls() {
        return Map.of();
    }
}
