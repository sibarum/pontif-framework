package sibarum.pontif.runtime.module;

import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.ir.NativeFunctions;
import sibarum.pontif.parser.AltParser;
import sibarum.pontif.parser.ParseException;

import java.util.Map;
import java.util.ServiceLoader;

/**
 * Installs {@link Extension}s into the runtime (docs/extensions.md). Install wires three things,
 * binding the Pontif interface to its Java objects <b>by name</b>:
 *
 * <ol>
 *   <li>the extension's {@linkplain Extension#pontifSource() module source} → the builtin module
 *       set ({@link BuiltinModules#registerExtensionModule}), so user code can {@code requires}
 *       it like any other module;</li>
 *   <li>each {@linkplain Extension#effects() effect} → {@link NativeFunctions}, keyed by the
 *       <b>fully-qualified</b> event type ({@code moduleName/EventName}), the form an emitted
 *       struct carries;</li>
 *   <li>each {@linkplain Extension#calls() call} → {@link NativeCalls}, under both its bare and
 *       module-qualified names, so a resolved decl name in either form finds its impl.</li>
 * </ol>
 *
 * <p>Install happens at startup, before compile/run: {@link IoExtension} is installed by default
 * (it has no external dependency); an external extension (the GUI) is installed by its launcher.
 */
public final class Extensions {

    private Extensions() {}

    private static boolean discovered = false;

    /**
     * Installs every {@link Extension} found on the classpath via {@link ServiceLoader} — each
     * builtin extension module ships a {@code META-INF/services/sibarum.pontif.runtime.module.Extension}
     * provider file listing its implementations. This is the <b>automatic</b> path: dropping a new
     * extension module on the classpath self-registers it, so no launcher, editor, or other entry
     * point needs editing to teach the runtime about a new module (docs/extensions.md).
     *
     * <p>Runs once (idempotent). A provider that fails to load, parse, or install is logged and
     * skipped, so one broken extension can't take down the whole runtime. Called from
     * {@link BuiltinModules}'s static initializer, so it happens before any module resolution on
     * every path; a context whose classpath has no extension modules (the lean CLI) simply finds
     * none.
     */
    public static synchronized void installDiscovered() {
        if (discovered) return;
        discovered = true;
        ServiceLoader.load(Extension.class, Extensions.class.getClassLoader())
                .stream()
                .forEach(provider -> {
                    try {
                        install(provider.get());
                    } catch (Throwable t) {
                        System.err.println("[pontif] extension provider "
                                + provider.type().getName() + " failed to install: " + t);
                    }
                });
    }

    /** Wires {@code ext}'s module + Java objects into the builtin set and the native registries. */
    public static void install(Extension ext) {
        String module = ext.moduleName();
        try {
            IrModule parsed = AltParser.parseModule(ext.pontifSource(), module);
            BuiltinModules.registerExtensionModule(
                    module, new IrModule(module, parsed.statements(), parsed.main()),
                    ext.pontifSource());
        } catch (ParseException pe) {
            throw new IllegalStateException(
                    "extension '" + module + "' source failed to parse: " + pe.getMessage(), pe);
        }
        for (Map.Entry<String, NativeFunctions.Effect> e : ext.effects().entrySet()) {
            NativeFunctions.register(module + "/" + e.getKey(), e.getValue());
        }
        for (Map.Entry<String, NativeCalls.NativeCall> c : ext.calls().entrySet()) {
            // Qualified name ONLY. Registering the bare name too would let a common function name
            // (pow, min, sign, …) from a math extension HIJACK a user's local function of the same
            // name — the resolved call name is always the FQN (module/name), so the qualified key
            // is what's looked up. (Bare registration silently overrode local functions.)
            NativeCalls.register(module + "/" + c.getKey(), c.getValue());
        }
    }
}
