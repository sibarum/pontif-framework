package sibarum.pontif.core.types;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The positional-aggregate (tuple) value rules both engines run.
 *
 * <p>It lives in {@code core} for the reason {@link CanonicalText} does: the interpreter works in
 * {@code pontif-ir} and the Truffle nodes in {@code pontif-ast}, neither can see the other, and a
 * second copy of a value rule is a divergence waiting to happen. Concatenation was exactly that —
 * {@code {1, 2} + {3, 4}} built {@code {1, 2, 3, 4}} on the interpreter and threw an internal
 * ClassCastException on Truffle, because only one engine had the rule.
 */
public final class Tuples {

    /** The structural-sort name of a positional aggregate, whose members are keyed {@code _0.._n}. */
    public static final String TUPLE = "_tuple";

    private Tuples() {}

    /** Whether {@code v} is a positional aggregate. */
    public static boolean isTuple(Object v) {
        return v instanceof RecordValue r && TUPLE.equals(r.typeName());
    }

    /**
     * Concatenates two positional streams into one, renumbering keys {@code _0.._n} — the
     * {@code +}-concatenates-sequences rule lifted to any Stream (docs/stream-war.md §7, slice 2e).
     * Structural, not per-element.
     */
    public static RecordValue concat(RecordValue a, RecordValue b) {
        Map<String, Object> members = new LinkedHashMap<>();
        int i = 0;
        for (Object v : a.members().values()) members.put("_" + i++, v);
        for (Object v : b.members().values()) members.put("_" + i++, v);
        return new RecordValue(TUPLE, members);
    }
}
