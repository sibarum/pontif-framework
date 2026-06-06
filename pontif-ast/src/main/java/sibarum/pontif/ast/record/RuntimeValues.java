package sibarum.pontif.ast.record;

import sibarum.pontif.core.symbolic.SymExpr;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime value → {@link SymExpr} conversion for the Truffle nodes that
 * judge concrete values against sorts at runtime (match arms, construction
 * claim checks). One conversion, shared, covering every scalar the runtime
 * produces plus records recursively.
 */
public final class RuntimeValues {

    private RuntimeValues() {}

    public static SymExpr toSymExpr(Object value) {
        if (value instanceof Long l) return SymExpr.lit(l);
        if (value instanceof Integer i) return SymExpr.lit(i.longValue());
        if (value instanceof BigDecimal d) return SymExpr.dec(d);
        if (value instanceof sibarum.pontif.core.types.CharValue c) {
            return SymExpr.chr(c.codePoint());
        }
        if (value instanceof Boolean b) return SymExpr.bool(b);
        if (value instanceof RecordValue r) {
            Map<String, SymExpr> members = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : r.members().entrySet()) {
                members.put(e.getKey(), toSymExpr(e.getValue()));
            }
            return SymExpr.record(r.typeName(), members);
        }
        throw new IllegalArgumentException(
                "Cannot convert runtime value to SymExpr (type "
                        + (value == null ? "null" : value.getClass().getSimpleName())
                        + "): " + value);
    }
}
