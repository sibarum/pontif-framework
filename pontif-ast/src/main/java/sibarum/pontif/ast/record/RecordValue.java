package sibarum.pontif.ast.record;

import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.RuntimeCheckException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class RecordValue {

    private final String typeName;
    private final Map<String, Object> members;

    public RecordValue(Map<String, Object> members) {
        this(null, members);
    }

    /**
     * @param typeName the nominal struct type name (e.g., {@code "Point"}),
     *                 or {@code null} for anonymous records. Used downstream
     *                 by the dispatch table's trait-fallback rule to identify
     *                 the concrete type of an argument.
     */
    public RecordValue(String typeName, Map<String, Object> members) {
        if (members == null) {
            throw new IllegalArgumentException("RecordValue members must be non-null");
        }
        this.typeName = typeName;
        this.members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
    }

    public String typeName() {
        return typeName;
    }

    public Map<String, Object> members() {
        return members;
    }

    public Object get(String fieldName, Origin accessOrigin) {
        if (!members.containsKey(fieldName)) {
            throw new RuntimeCheckException(
                    "Record has no field '" + fieldName + "'; available fields: " + members.keySet(),
                    accessOrigin);
        }
        return members.get(fieldName);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RecordValue r && members.equals(r.members);
    }

    @Override
    public int hashCode() {
        return Objects.hash(members);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (typeName != null) sb.append(typeName);
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : members.entrySet()) {
            if (!first) sb.append(", ");
            Object v = e.getValue();
            String rendered = v instanceof java.math.BigDecimal d
                    ? sibarum.pontif.core.Decimals.display(d)
                    : String.valueOf(v);
            sb.append(e.getKey()).append(": ").append(rendered);
            first = false;
        }
        return sb.append("}").toString();
    }
}
