package sibarum.pontif.core.symbolic;

import sibarum.pontif.core.types.Sort;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Substitute {

    private Substitute() {}

    public static SymExpr apply(SymExpr expr, Map<String, SymExpr> bindings) {
        return switch (expr) {
            case SymExpr.Var v -> bindings.getOrDefault(v.name(), v);
            case SymExpr.Lit l -> l;
            case SymExpr.Frac f -> f;
            case SymExpr.Bool b -> b;
            case SymExpr.Self s -> s;
            case SymExpr.Add(SymExpr l, SymExpr r) -> new SymExpr.Add(apply(l, bindings), apply(r, bindings));
            case SymExpr.Mul(SymExpr l, SymExpr r) -> new SymExpr.Mul(apply(l, bindings), apply(r, bindings));
            case SymExpr.Pow(SymExpr b, SymExpr e) -> new SymExpr.Pow(apply(b, bindings), apply(e, bindings));
            case SymExpr.Cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r) -> new SymExpr.Cmp(apply(l, bindings), op, apply(r, bindings));
            case SymExpr.And(SymExpr l, SymExpr r) -> new SymExpr.And(apply(l, bindings), apply(r, bindings));
            case SymExpr.Or(SymExpr l, SymExpr r) -> new SymExpr.Or(apply(l, bindings), apply(r, bindings));
            case SymExpr.App(SymExpr fn, SymExpr arg) -> new SymExpr.App(apply(fn, bindings), apply(arg, bindings));
            case SymExpr.Lam(String param, Sort paramType, SymExpr body) -> substituteIntoLam(param, paramType, body, bindings);
            case SymExpr.Case(SymExpr scrutinee, List<SymExpr.CaseBranch> branches) -> {
                SymExpr newScrutinee = apply(scrutinee, bindings);
                List<SymExpr.CaseBranch> newBranches = branches.stream()
                        .map(b -> new SymExpr.CaseBranch(
                                substituteIntoSort(b.pattern(), bindings),
                                apply(b.result(), bindings)))
                        .toList();
                yield new SymExpr.Case(newScrutinee, newBranches);
            }
            case SymExpr.Record(Map<String, SymExpr> members) -> {
                Map<String, SymExpr> newMembers = new LinkedHashMap<>();
                for (Map.Entry<String, SymExpr> e : members.entrySet()) {
                    newMembers.put(e.getKey(), apply(e.getValue(), bindings));
                }
                yield new SymExpr.Record(newMembers);
            }
            case SymExpr.FieldAccess(SymExpr base, String name) -> new SymExpr.FieldAccess(apply(base, bindings), name);
        };
    }

    public static SymExpr applySelf(SymExpr expr, SymExpr value) {
        return switch (expr) {
            case SymExpr.Self s -> value;
            case SymExpr.Var v -> v;
            case SymExpr.Lit l -> l;
            case SymExpr.Frac f -> f;
            case SymExpr.Bool b -> b;
            case SymExpr.Add(SymExpr l, SymExpr r) -> new SymExpr.Add(applySelf(l, value), applySelf(r, value));
            case SymExpr.Mul(SymExpr l, SymExpr r) -> new SymExpr.Mul(applySelf(l, value), applySelf(r, value));
            case SymExpr.Pow(SymExpr b, SymExpr e) -> new SymExpr.Pow(applySelf(b, value), applySelf(e, value));
            case SymExpr.Cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r) -> new SymExpr.Cmp(applySelf(l, value), op, applySelf(r, value));
            case SymExpr.And(SymExpr l, SymExpr r) -> new SymExpr.And(applySelf(l, value), applySelf(r, value));
            case SymExpr.Or(SymExpr l, SymExpr r) -> new SymExpr.Or(applySelf(l, value), applySelf(r, value));
            case SymExpr.App(SymExpr fn, SymExpr arg) -> new SymExpr.App(applySelf(fn, value), applySelf(arg, value));
            case SymExpr.Lam(String param, Sort paramType, SymExpr body) -> new SymExpr.Lam(param, paramType, applySelf(body, value));
            case SymExpr.Case(SymExpr scrutinee, List<SymExpr.CaseBranch> branches) ->
                    new SymExpr.Case(applySelf(scrutinee, value), branches);
            case SymExpr.Record(Map<String, SymExpr> members) -> {
                Map<String, SymExpr> newMembers = new LinkedHashMap<>();
                for (Map.Entry<String, SymExpr> e : members.entrySet()) {
                    newMembers.put(e.getKey(), applySelf(e.getValue(), value));
                }
                yield new SymExpr.Record(newMembers);
            }
            case SymExpr.FieldAccess(SymExpr base, String name) -> new SymExpr.FieldAccess(applySelf(base, value), name);
        };
    }

    private static Sort substituteIntoSort(Sort sort, Map<String, SymExpr> bindings) {
        if (!sort.isRefined()) {
            return sort;
        }
        return Sort.refined(sort.name(), apply(sort.predicate(), bindings));
    }

    private static SymExpr substituteIntoLam(
            String param,
            Sort paramType,
            SymExpr body,
            Map<String, SymExpr> bindings) {
        Map<String, SymExpr> inner = new HashMap<>(bindings);
        inner.remove(param);

        if (inner.isEmpty()) {
            return new SymExpr.Lam(param, paramType, body);
        }

        boolean wouldCapture = inner.values().stream()
                .anyMatch(value -> AlphaRename.isFreeIn(param, value));

        if (wouldCapture) {
            SymExpr[] avoiding = new SymExpr[inner.values().size() + 1];
            avoiding[0] = body;
            int idx = 1;
            for (SymExpr v : inner.values()) {
                avoiding[idx++] = v;
            }
            String freshParam = AlphaRename.freshName(param, avoiding);
            SymExpr renamedBody = apply(body, Map.of(param, SymExpr.var(freshParam)));
            return new SymExpr.Lam(freshParam, paramType, apply(renamedBody, inner));
        }

        return new SymExpr.Lam(param, paramType, apply(body, inner));
    }
}
