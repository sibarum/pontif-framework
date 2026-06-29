package sibarum.pontif.runtime.module;

import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.ir.NativeFunctions;
import sibarum.pontif.parser.AltParser;
import sibarum.pontif.parser.ParseException;

import java.util.Map;

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

    /** Wires {@code ext}'s module + Java objects into the builtin set and the native registries. */
    public static void install(Extension ext) {
        String module = ext.moduleName();
        try {
            IrModule parsed = AltParser.parseModule(ext.pontifSource(), module);
            BuiltinModules.registerExtensionModule(
                    module, new IrModule(module, parsed.statements(), parsed.main()));
        } catch (ParseException pe) {
            throw new IllegalStateException(
                    "extension '" + module + "' source failed to parse: " + pe.getMessage(), pe);
        }
        for (Map.Entry<String, NativeFunctions.Effect> e : ext.effects().entrySet()) {
            NativeFunctions.register(module + "/" + e.getKey(), e.getValue());
        }
        for (Map.Entry<String, NativeCalls.NativeCall> c : ext.calls().entrySet()) {
            NativeCalls.register(c.getKey(), c.getValue());
            NativeCalls.register(module + "/" + c.getKey(), c.getValue());
        }
    }
}
