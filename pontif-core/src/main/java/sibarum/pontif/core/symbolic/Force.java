package sibarum.pontif.core.symbolic;

public final class Force {

    private Force() {}

    public static Object apply(SymExpr expr) {
        return switch (expr) {
            case SymExpr.Var v -> throw new UnresolvedSymbolException("Unresolved symbol: " + v.name());
            case SymExpr.Lit l -> l.value();
            case SymExpr.Bool b -> b.value();
            case SymExpr.Self s -> throw new UnresolvedSymbolException("Cannot force Self — it is a typing-context placeholder, not a value");
            case SymExpr.Frac f -> {
                if (f.denom() == 1) {
                    yield f.num();
                }
                throw new UnresolvedSymbolException(
                        "Cannot force non-integer rational " + f.num() + "/" + f.denom() + " to a primitive");
            }
            case SymExpr.Add(SymExpr l, SymExpr r) -> (Long) apply(l) + (Long) apply(r);
            case SymExpr.Mul(SymExpr l, SymExpr r) -> (Long) apply(l) * (Long) apply(r);
            case SymExpr.Pow(SymExpr b, SymExpr e) -> {
                long base = (Long) apply(b);
                long exponent = (Long) apply(e);
                if (exponent < 0) {
                    throw new UnresolvedSymbolException(
                            "Cannot force negative-exponent Pow to a primitive: " + base + "^" + exponent);
                }
                long result = 1;
                for (long i = 0; i < exponent; i++) {
                    result *= base;
                }
                yield result;
            }
            case SymExpr.Cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r) -> {
                long ll = (Long) apply(l);
                long rr = (Long) apply(r);
                yield switch (op) {
                    case LT -> ll < rr;
                    case LE -> ll <= rr;
                    case GT -> ll > rr;
                    case GE -> ll >= rr;
                    case EQ -> ll == rr;
                    case NE -> ll != rr;
                };
            }
            case SymExpr.Lam l ->
                    throw new UnresolvedSymbolException(
                            "Cannot force a lambda to a primitive; lambdas are values, not groundable terms (param: " + l.param() + ")");
            case SymExpr.App a ->
                    throw new UnresolvedSymbolException(
                            "Cannot force an unevaluated application; run Simplifier first to beta-reduce");
            case SymExpr.Case c ->
                    throw new UnresolvedSymbolException(
                            "Cannot force an unreduced Case; simplifier left it residual (symbolic scrutinee or no matching branch)");
            case SymExpr.Record r ->
                    throw new UnresolvedSymbolException(
                            "Cannot force a Record to a primitive — records are structured values");
            case SymExpr.FieldAccess f ->
                    throw new UnresolvedSymbolException(
                            "Cannot force an unreduced FieldAccess; the base did not simplify to a Record literal");
        };
    }
}
