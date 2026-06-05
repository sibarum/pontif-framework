package sibarum.pontif.core.symbolic;

import sibarum.pontif.core.types.Sort;

import java.util.List;
import java.util.Map;

public final class AlphaRename {

    private AlphaRename() {}

    public static boolean isFreeIn(String name, SymExpr expr) {
        return switch (expr) {
            case SymExpr.Var v -> v.name().equals(name);
            case SymExpr.Lit l -> false;
            case SymExpr.Frac f -> false;
            case SymExpr.Dec d -> false;
            case SymExpr.Chr c -> false;
            case SymExpr.DispatchRef d -> false;
            case SymExpr.Bool b -> false;
            case SymExpr.Self s -> false;
            case SymExpr.Add(SymExpr l, SymExpr r) -> isFreeIn(name, l) || isFreeIn(name, r);
            case SymExpr.Mul(SymExpr l, SymExpr r) -> isFreeIn(name, l) || isFreeIn(name, r);
            case SymExpr.Pow(SymExpr b, SymExpr e) -> isFreeIn(name, b) || isFreeIn(name, e);
            case SymExpr.Cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r) -> isFreeIn(name, l) || isFreeIn(name, r);
            case SymExpr.And(SymExpr l, SymExpr r) -> isFreeIn(name, l) || isFreeIn(name, r);
            case SymExpr.Or(SymExpr l, SymExpr r) -> isFreeIn(name, l) || isFreeIn(name, r);
            case SymExpr.Lam(String param, Sort paramType, SymExpr body) ->
                    !param.equals(name) && isFreeIn(name, body);
            case SymExpr.App(SymExpr fn, SymExpr arg) -> isFreeIn(name, fn) || isFreeIn(name, arg);
            case SymExpr.Case(SymExpr scrutinee, List<SymExpr.CaseBranch> branches) -> {
                if (isFreeIn(name, scrutinee)) yield true;
                boolean found = false;
                for (SymExpr.CaseBranch b : branches) {
                    if (b.pattern().isRefined() && isFreeIn(name, b.pattern().predicate())) { found = true; break; }
                    if (isFreeIn(name, b.result())) { found = true; break; }
                }
                yield found;
            }
            case SymExpr.Record(Map<String, SymExpr> members, String typeName) -> {
                boolean found = false;
                for (SymExpr v : members.values()) {
                    if (isFreeIn(name, v)) { found = true; break; }
                }
                yield found;
            }
            case SymExpr.FieldAccess(SymExpr base, String fieldName) -> isFreeIn(name, base);
        };
    }

    public static String freshName(String baseName, SymExpr... avoiding) {
        int i = 0;
        while (true) {
            String candidate = baseName + "$" + i;
            boolean clean = true;
            for (SymExpr e : avoiding) {
                if (isFreeIn(candidate, e)) {
                    clean = false;
                    break;
                }
            }
            if (clean) {
                return candidate;
            }
            i++;
        }
    }
}
