package sibarum.pontif.ast.record;

import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.RuntimeCheckException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class RecordValue {

    private final Map<String, Object> members;

    public RecordValue(Map<String, Object> members) {
        if (members == null) {
            throw new IllegalArgumentException("RecordValue members must be non-null");
        }
        this.members = Map.copyOf(members);
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
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        // Iterate via a fresh LinkedHashMap to keep toString output stable when input
        // was insertion-ordered. Map.copyOf preserves source order for LinkedHashMap
        // inputs but not for arbitrary Maps; toString stability is best-effort.
        for (Map.Entry<String, Object> e : new LinkedHashMap<>(members).entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append(": ").append(e.getValue());
            first = false;
        }
        return sb.append("}").toString();
    }
}
