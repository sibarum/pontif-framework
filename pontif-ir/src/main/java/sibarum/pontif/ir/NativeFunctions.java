package sibarum.pontif.ir;

import sibarum.pontif.ast.record.RecordValue;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.types.StringValue;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The native <b>function</b> registry — the side-effecting counterpart to
 * {@link NativeConstructors}, kept deliberately separate (NativeConstructors §):
 * a constructor entry is a total-exact bijection contract; a native function entry
 * carries an effect and an (eventual) axiomatic conservation summary. This is that
 * registry's first tenant.
 *
 * <p>It backs the event substrate's first conduits (docs/events.md, slice 1b —
 * output IO). An {@code emit EVENT} statement ({@link IrExpr.Emit}) routes
 * <b>by the event's type name</b> to the matching effect here; the builtin
 * {@code StdOut} / {@code StdErr} events write their {@code text} field to the
 * process's standard streams. {@code System.out}/{@code System.err} are read
 * <em>at call time</em> (inside the lambda), so test redirection via
 * {@code System.setOut} is honoured.
 *
 * <p>The stateful conduit fold, the user-defined {@code EventConduit} contract,
 * and the input (stdin / pull) side are later slices; an event with no registered
 * effect fails closed at the {@code emit}.
 */
public final class NativeFunctions {

    /** One native effect: perform the side-effect for an emitted event. */
    @FunctionalInterface
    public interface Effect {
        /** Routes the emitted event; the result is discarded (emit is write-only). */
        Object apply(RecordValue event, Origin origin);
    }

    private static final Map<String, Effect> ENTRIES = new LinkedHashMap<>();

    static {
        // The first conduits (docs/events.md): the two write-only output streams.
        // System.out / System.err are referenced inside the lambda — i.e. read at
        // call time — so a test's System.setOut redirection takes effect.
        register("StdOut", (event, origin) -> writeText(System.out, event, origin));
        register("StdErr", (event, origin) -> writeText(System.err, event, origin));
    }

    private NativeFunctions() {}

    private static void register(String eventType, Effect effect) {
        ENTRIES.put(eventType, effect);
    }

    /** Whether an event of this (possibly module-qualified) type name has a conduit. */
    public static boolean has(String eventTypeName) {
        String s = simpleName(eventTypeName);
        return s != null && ENTRIES.containsKey(s);
    }

    /** The effect for an event type name, or null if none is registered. */
    public static Effect get(String eventTypeName) {
        String s = simpleName(eventTypeName);
        return s == null ? null : ENTRIES.get(s);
    }

    /**
     * Cross-module construction qualifies the nominal ("pontif.events/StdOut"); a
     * same-module use is bare. Route by the simple name — the same bare-or-qualified
     * rule {@link IrInterpreter} uses for {@code Nothing}.
     */
    private static String simpleName(String name) {
        if (name == null) return null;
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private static Object writeText(PrintStream out, RecordValue event, Origin origin) {
        Object text = event.members().get("text");
        if (!(text instanceof StringValue s)) {
            throw new RuntimeCheckException(
                    "Output event '" + event.typeName() + "' must carry a String 'text' field; got "
                            + (text == null ? "no 'text' field" : text.getClass().getSimpleName()),
                    origin);
        }
        out.print(s.content());
        // emit is write-only — the result is never bound; return the Nothing omission
        // value for honesty (the emit node discards it).
        return new RecordValue("Nothing", new LinkedHashMap<>());
    }
}
