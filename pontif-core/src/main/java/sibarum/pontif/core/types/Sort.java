package sibarum.pontif.core.types;

import sibarum.pontif.core.symbolic.SymExpr;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record Sort(
        String name,
        SymExpr predicate,
        Map<String, Sort> members,
        List<Sort> functionParams,
        Sort functionReturnSort) {

    public Sort {
        if (members != null) {
            // Preserve insertion order — struct field iteration order is
            // load-bearing for destructure / construction; Map.copyOf would
            // silently strip it.
            members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
        }
        if (functionParams != null) {
            functionParams = List.copyOf(functionParams);
        }
    }

    public static Sort of(String name) {
        return new Sort(name, null, null, null, null);
    }

    public static Sort refined(String name, SymExpr predicate) {
        return new Sort(name, predicate, null, null, null);
    }

    public static Sort structural(String name, Map<String, Sort> members) {
        return new Sort(name, null, members, null, null);
    }

    public static Sort function(List<Sort> params, Sort returnSort) {
        StringBuilder n = new StringBuilder("(");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) n.append(", ");
            n.append(params.get(i));
        }
        n.append(") -> ").append(returnSort);
        return new Sort(n.toString(), null, null, params, returnSort);
    }

    public boolean isRefined() {
        return predicate != null;
    }

    public boolean isStructural() {
        return members != null;
    }

    public boolean isFunction() {
        return functionReturnSort != null;
    }

    @Override
    public String toString() {
        if (functionReturnSort != null) {
            return name;
        }
        if (members != null) {
            StringBuilder sb = new StringBuilder(name).append("{");
            boolean first = true;
            for (Map.Entry<String, Sort> e : members.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getKey()).append(": ").append(e.getValue());
                first = false;
            }
            return sb.append("}").toString();
        }
        if (predicate == null) {
            return name;
        }
        return name + "[" + predicate + "]";
    }
}
