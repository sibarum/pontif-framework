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
        Sort functionReturnSort,
        List<Sort> unionBranches,
        List<Sort> intersectionBranches) {

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
        if (unionBranches != null) {
            unionBranches = List.copyOf(unionBranches);
        }
        if (intersectionBranches != null) {
            intersectionBranches = List.copyOf(intersectionBranches);
        }
    }

    public static Sort of(String name) {
        return new Sort(name, null, null, null, null, null, null);
    }

    public static Sort refined(String name, SymExpr predicate) {
        return new Sort(name, predicate, null, null, null, null, null);
    }

    public static Sort structural(String name, Map<String, Sort> members) {
        return new Sort(name, null, members, null, null, null, null);
    }

    public static Sort function(List<Sort> params, Sort returnSort) {
        StringBuilder n = new StringBuilder("(");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) n.append(", ");
            n.append(params.get(i));
        }
        n.append(") -> ").append(returnSort);
        return new Sort(n.toString(), null, null, params, returnSort, null, null);
    }

    /**
     * Cross-base union sort. Values satisfy the union iff they satisfy at
     * least one branch. Used for cases like {@code [Int|Float]} where
     * branches don't share a common base (same-base unions normalize to a
     * single refined sort at parse time).
     */
    public static Sort union(List<Sort> branches) {
        if (branches == null || branches.size() < 2) {
            throw new IllegalArgumentException(
                    "Union must have at least two branches");
        }
        StringBuilder n = new StringBuilder();
        for (int i = 0; i < branches.size(); i++) {
            if (i > 0) n.append(" | ");
            n.append(branches.get(i));
        }
        return new Sort(n.toString(), null, null, null, null, branches, null);
    }

    /**
     * Cross-base intersection sort. Values satisfy the intersection iff
     * they satisfy every branch.
     */
    public static Sort intersection(List<Sort> branches) {
        if (branches == null || branches.size() < 2) {
            throw new IllegalArgumentException(
                    "Intersection must have at least two branches");
        }
        StringBuilder n = new StringBuilder();
        for (int i = 0; i < branches.size(); i++) {
            if (i > 0) n.append(" & ");
            n.append(branches.get(i));
        }
        return new Sort(n.toString(), null, null, null, null, null, branches);
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

    public boolean isUnion() {
        return unionBranches != null;
    }

    public boolean isIntersection() {
        return intersectionBranches != null;
    }

    @Override
    public String toString() {
        if (functionReturnSort != null) {
            return name;
        }
        if (members != null) {
            // Tuples (the "_tuple" sentinel) are anonymous positional
            // aggregates — render them as `(A, B)` from their _0.._n members
            // rather than `_tuple{_0: A, _1: B}`.
            if ("_tuple".equals(name)) {
                StringBuilder sb = new StringBuilder("(");
                boolean first = true;
                for (Sort member : members.values()) {
                    if (!first) sb.append(", ");
                    sb.append(member);
                    first = false;
                }
                return sb.append(")").toString();
            }
            StringBuilder sb = new StringBuilder(name).append("{");
            boolean first = true;
            for (Map.Entry<String, Sort> e : members.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getKey()).append(": ").append(e.getValue());
                first = false;
            }
            return sb.append("}").toString();
        }
        if (unionBranches != null || intersectionBranches != null) {
            return name;
        }
        if (predicate == null) {
            return name;
        }
        return name + "[" + predicate + "]";
    }
}
