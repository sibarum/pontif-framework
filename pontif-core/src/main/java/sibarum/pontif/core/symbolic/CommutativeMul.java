package sibarum.pontif.core.symbolic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CommutativeMul {

    private CommutativeMul() {}

    public static SymExpr normalize(SymExpr expr) {
        if (!(expr instanceof SymExpr.Mul)) {
            return expr;
        }
        List<SymExpr> factors = flatten(expr);
        factors.sort(Comparator.comparing(CommutativeMul::sortKey));
        List<SymExpr> merged = mergeSameBaseRuns(factors);
        return rebuildLeftAssociative(merged);
    }

    public static List<SymExpr> flatten(SymExpr expr) {
        List<SymExpr> result = new ArrayList<>();
        flattenInto(expr, result);
        return result;
    }

    private static void flattenInto(SymExpr expr, List<SymExpr> sink) {
        if (expr instanceof SymExpr.Mul(SymExpr l, SymExpr r)) {
            flattenInto(l, sink);
            flattenInto(r, sink);
        } else {
            sink.add(expr);
        }
    }

    private static String sortKey(SymExpr e) {
        SymExpr base = (e instanceof SymExpr.Pow p) ? p.base() : e;
        return base.toString() + "|" + e.toString();
    }

    private static List<SymExpr> mergeSameBaseRuns(List<SymExpr> factors) {
        List<SymExpr> merged = new ArrayList<>();
        for (SymExpr factor : factors) {
            if (merged.isEmpty()) {
                merged.add(factor);
                continue;
            }
            SymExpr last = merged.getLast();
            if (last instanceof SymExpr.Pow(SymExpr b1, SymExpr e1)
                    && factor instanceof SymExpr.Pow(SymExpr b2, SymExpr e2)
                    && b1.equals(b2)) {
                merged.set(merged.size() - 1, SymExpr.pow(b1, SymExpr.add(e1, e2)));
            } else {
                merged.add(factor);
            }
        }
        return merged;
    }

    private static SymExpr rebuildLeftAssociative(List<SymExpr> factors) {
        if (factors.isEmpty()) {
            throw new IllegalStateException("Cannot rebuild empty product");
        }
        SymExpr result = factors.get(0);
        for (int i = 1; i < factors.size(); i++) {
            result = SymExpr.mul(result, factors.get(i));
        }
        return result;
    }
}
