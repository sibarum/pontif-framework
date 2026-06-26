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
 * {@code pontif.events/StdOut} / {@code StdErr} events write their {@code text}
 * field to the process's standard streams. {@code System.out}/{@code System.err}
 * are read <em>at call time</em> (inside the lambda), so test redirection via
 * {@code System.setOut} is honoured.
 *
 * <p><b>Routing keys on the fully-qualified event type</b> ({@code module/Name}, the
 * form an imported struct carries at eval) — an exact match, never a bare-name or
 * suffix match. This is what makes "one conduit per event type" honest: a user's own
 * {@code struct StdOut} in some other module is a <em>different</em> type
 * ({@code thatModule/StdOut}) and does NOT route here — it fails closed at the
 * {@code emit}, rather than silently hijacking the process streams.
 *
 * <p>The stateful conduit fold, the user-defined {@code EventConduit} contract, and
 * the input (stdin / pull) side are later slices.
 */
public final class NativeFunctions {

    /** One native effect: perform the side-effect for an emitted event (write-only). */
    @FunctionalInterface
    public interface Effect {
        void apply(RecordValue event, Origin origin);
    }

    /** The defining module of the builtin output conduits (mirrors BuiltinModules.PONTIF_EVENTS). */
    private static final String EVENTS_MODULE = "pontif.events";

    private static final Map<String, Effect> ENTRIES = new LinkedHashMap<>();

    static {
        // The first conduits (docs/events.md): the two write-only output streams,
        // keyed by their fully-qualified type. System.out / System.err are read
        // inside the lambda — at call time — so a test's System.setOut redirection
        // takes effect.
        ENTRIES.put(EVENTS_MODULE + "/StdOut", (event, origin) -> writeText(System.out, event, origin));
        ENTRIES.put(EVENTS_MODULE + "/StdErr", (event, origin) -> writeText(System.err, event, origin));
    }

    private NativeFunctions() {}

    /**
     * The conduit for an event type, or null if none — an <b>exact</b> match on the
     * fully-qualified type name (no qualifier stripping): only the genuine
     * {@code pontif.events} conduit events route, never a same-named struct from
     * another module.
     */
    public static Effect get(String eventTypeName) {
        return eventTypeName == null ? null : ENTRIES.get(eventTypeName);
    }

    private static void writeText(PrintStream out, RecordValue event, Origin origin) {
        Object text = event.members().get("text");
        if (!(text instanceof StringValue s)) {
            throw new RuntimeCheckException(
                    "Output event '" + event.typeName() + "' must carry a String 'text' field; got "
                            + (text == null ? "no 'text' field" : text.getClass().getSimpleName()),
                    origin);
        }
        out.print(s.content());
    }
}
