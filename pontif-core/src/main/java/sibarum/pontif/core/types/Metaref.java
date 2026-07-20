package sibarum.pontif.core.types;

import java.util.List;
import java.util.Map;

/**
 * The runtime representation of a metareference ({@code $f[keys]}) as a first-class
 * <b>object</b> — a {@link RecordValue} carrying its concrete nominal type and dispatch
 * payload, per the ratified rule "anything that looks like an object should actually be
 * one" (docs/dispatch-method-elimination.md; memory {@code values-are-recordvalues}). This
 * is the single runtime representation of a metareference across every layer — the
 * tree-walking interpreter, the symbolic {@code Force} grounding, and the Truffle mirror —
 * having retired the bespoke {@code DispatchValue}.
 *
 * <p>A metaref record's {@code typeName} is the concrete dispatch nominal ({@link #DISPATCH}
 * for a plain metareference, {@link #ALGEBRAIC_DISPATCH} for one proven algebraic); its
 * members hold the dispatch payload under the reserved keys {@link #FN}/{@link #KEYS} (the
 * leading {@code $} keeps them un-spellable as user fields, and the compile-time member gate
 * only admits the type's declared attributes anyway, so they never surface). Because it IS a
 * {@code RecordValue}, member access — notably the {@code .ast} attribute of {@code Algebraic}
 * — flows through the stock attribute-producer path with no interpreter special-case.
 */
public final class Metaref {

    /**
     * The concrete nominal of a plain (non-algebraic) metareference — the builtin
     * {@code Dispatch}. (An earlier plan named a distinct {@code DispatchBase}, but reusing
     * {@code Dispatch} keeps every existing metareference-as-Dispatch check unchanged; the
     * only distinction the {@code .ast} feature needs is algebraic vs not.)
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
        return of(functionName, keySorts, algebraic ? ALGEBRAIC_DISPATCH : DISPATCH);
    }

    /**
     * Builds the metaref record with an explicit concrete nominal — used where the nominal
     * is already known (e.g. carried on a {@code SymExpr.DispatchRef}); a {@code null}
     * nominal defaults to the plain {@link #DISPATCH}.
     */
    public static RecordValue of(String functionName, List<Sort> keySorts, String typeName) {
        return new RecordValue(
                typeName == null ? DISPATCH : typeName,
                Map.of(FN, functionName, KEYS, List.copyOf(keySorts)));
    }

    /** Whether {@code v} is a metareference record. */
    public static boolean is(Object v) {
        return v instanceof RecordValue r && r.members().containsKey(FN);
    }

    /** The referent function name of a metareference, or null if {@code v} is not one. */
    public static String functionName(Object v) {
        return v instanceof RecordValue r && r.members().get(FN) instanceof String fn ? fn : null;
    }

    /** The key sorts of a metareference, or null if {@code v} is not one. */
    @SuppressWarnings("unchecked")
    public static List<Sort> keySorts(Object v) {
        if (v instanceof RecordValue r && r.members().get(KEYS) instanceof List<?> ks) {
            return (List<Sort>) ks;
        }
        return null;
    }
}
