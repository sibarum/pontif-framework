package sibarum.pontif.ast.record;

import sibarum.pontif.core.types.DispatchValue;
import sibarum.pontif.core.types.Sort;

import java.util.List;
import java.util.Map;

/**
 * The runtime representation of a metareference ({@code $f[keys]}) as a first-class
 * <b>object</b> — a {@link RecordValue} carrying its concrete nominal type and dispatch
 * payload, per the ratified rule "anything that looks like an object should actually be
 * one" (docs/dispatch-method-elimination.md E2; memory {@code values-are-recordvalues}).
 *
 * <p>A metaref record's {@code typeName} is the concrete dispatch nominal
 * ({@link #DISPATCH_BASE} for a plain metareference, {@link #ALGEBRAIC_DISPATCH} for one
 * proven algebraic); its members hold the dispatch payload under the reserved keys
 * {@link #FN}/{@link #KEYS} (the leading {@code $} keeps them un-spellable as user fields,
 * and the compile-time member gate only admits the type's declared attributes anyway, so
 * they never surface). Because it IS a {@code RecordValue}, member access — notably the
 * {@code .ast} attribute of {@code Algebraic} — flows through the stock attribute-producer
 * path with no interpreter special-case.
 *
 * <p><b>Transitional:</b> the symbolic {@code Force} layer (pontif-core, below
 * {@code RecordValue}) still grounds a {@link DispatchValue}; until that layer is migrated
 * in the following substrate commit, {@link #is}/{@link #functionName}/{@link #keySorts}
 * accept EITHER representation so ir consumers work with both.
 */
public final class Metaref {

    /**
     * The concrete nominal of a plain (non-algebraic) metareference — the builtin
     * {@code Dispatch}. (An earlier plan named a distinct {@code DispatchBase}, but reusing
     * {@code Dispatch} keeps every existing metareference-as-Dispatch check unchanged; the
     * only distinction E2 needs is algebraic vs not.)
     */
    public static final String DISPATCH = "Dispatch";
    /** The concrete nominal of a metareference proven algebraic (is-a {@code Algebraic}). */
    public static final String ALGEBRAIC_DISPATCH = "AlgebraicDispatch";

    /** Reserved member key: the referent function name. */
    private static final String FN = "$dispatchFn";
    /** Reserved member key: the key sorts the reference was taken at. */
    private static final String KEYS = "$dispatchKeys";

    private Metaref() {}

    /** Builds the metaref record — {@code typeName} is {@link #ALGEBRAIC_DISPATCH} iff {@code algebraic}. */
    public static RecordValue of(String functionName, List<Sort> keySorts, boolean algebraic) {
        return new RecordValue(
                algebraic ? ALGEBRAIC_DISPATCH : DISPATCH,
                Map.of(FN, functionName, KEYS, List.copyOf(keySorts)));
    }

    /** Whether {@code v} is a metareference — a metaref record or a legacy {@link DispatchValue}. */
    public static boolean is(Object v) {
        return v instanceof DispatchValue
                || (v instanceof RecordValue r && r.members().containsKey(FN));
    }

    /** The referent function name of a metareference (either representation), or null if not one. */
    public static String functionName(Object v) {
        if (v instanceof DispatchValue dv) return dv.functionName();
        if (v instanceof RecordValue r && r.members().get(FN) instanceof String fn) return fn;
        return null;
    }

    /** The key sorts of a metareference (either representation), or null if not one. */
    @SuppressWarnings("unchecked")
    public static List<Sort> keySorts(Object v) {
        if (v instanceof DispatchValue dv) return dv.keySorts();
        if (v instanceof RecordValue r && r.members().get(KEYS) instanceof List<?> ks) {
            return (List<Sort>) ks;
        }
        return null;
    }
}
