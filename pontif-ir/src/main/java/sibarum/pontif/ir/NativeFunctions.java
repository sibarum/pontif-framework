package sibarum.pontif.ir;

import sibarum.pontif.ast.record.RecordValue;
import sibarum.pontif.core.Origin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The native <b>effect</b> registry — emit sinks, keyed by the fully-qualified event type.
 * An {@code emit EVENT} statement ({@link IrExpr.Emit}) routes <b>by the event's type name</b>
 * to the matching {@link Effect} here.
 *
 * <p><b>Populated by extensions, not hardcoded</b> (docs/extensions.md, the side-effect API):
 * the builtin IO effects ({@code pontif.events/StdOut} / {@code StdErr}) are registered by the
 * {@code IoExtension} like any other; the GUI and future effects register the same way. The
 * registry is the runtime-facing seam an extension's Java objects bind into; the Pontif-side
 * interface and the by-name binding live in the extension manifest.
 *
 * <p><b>Routing keys on the fully-qualified event type</b> ({@code module/Name}, the form an
 * imported struct carries at eval) — an exact match, never a bare-name or suffix match. This is
 * what makes "one conduit per event type" honest: a user's own {@code struct StdOut} in some
 * other module is a <em>different</em> type and does NOT route here — it fails closed at the
 * {@code emit}, rather than silently hijacking the process streams.
 */
public final class NativeFunctions {

    /** One native effect: perform the side-effect for an emitted event (write-only). */
    @FunctionalInterface
    public interface Effect {
        void apply(RecordValue event, Origin origin);
    }

    private static final Map<String, Effect> ENTRIES = new LinkedHashMap<>();

    private NativeFunctions() {}

    /** Binds {@code effect} to the fully-qualified {@code eventTypeName} (extension install). */
    public static void register(String eventTypeName, Effect effect) {
        ENTRIES.put(eventTypeName, effect);
    }

    /**
     * The effect for an event type, or null if none — an <b>exact</b> match on the
     * fully-qualified type name (no qualifier stripping): only the genuine registered conduit
     * events route, never a same-named struct from another module.
     */
    public static Effect get(String eventTypeName) {
        return eventTypeName == null ? null : ENTRIES.get(eventTypeName);
    }
}
