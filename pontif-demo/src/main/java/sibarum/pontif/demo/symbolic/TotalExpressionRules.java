package sibarum.pontif.demo.symbolic;

import sibarum.pontif.core.symbolic.CommutativeMul;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.SymExpr;

import java.util.List;
import java.util.Optional;

public final class TotalExpressionRules {

    private TotalExpressionRules() {}

    public static final RewriteRule FRAC_NORM = (expr, simp) -> {
        if (expr instanceof SymExpr.Frac(long n, long d) && d == 1) {
            return Optional.of(SymExpr.lit(n));
        }
        return Optional.empty();
    };

    public static final RewriteRule LIT_TO_FRAC_FOR_ADD = (expr, simp) -> {
        if (expr instanceof SymExpr.Add(SymExpr.Lit(long n), SymExpr.Frac f)) {
            return Optional.of(SymExpr.add(SymExpr.frac(n, 1), f));
        }
        if (expr instanceof SymExpr.Add(SymExpr.Frac f, SymExpr.Lit(long n))) {
            return Optional.of(SymExpr.add(f, SymExpr.frac(n, 1)));
        }
        return Optional.empty();
    };

    public static final RewriteRule FRAC_ADD = (expr, simp) -> {
        if (expr instanceof SymExpr.Add(SymExpr.Frac(long a, long b), SymExpr.Frac(long c, long d))) {
            return Optional.of(SymExpr.frac(a * d + b * c, b * d));
        }
        return Optional.empty();
    };

    public static final RewriteRule POW_OF_ONE_EXP = (expr, simp) -> {
        if (expr instanceof SymExpr.Pow(SymExpr a, SymExpr.Lit(long n)) && n == 1) {
            return Optional.of(a);
        }
        if (expr instanceof SymExpr.Pow(SymExpr a, SymExpr.Frac(long n, long d)) && n == 1 && d == 1) {
            return Optional.of(a);
        }
        return Optional.empty();
    };

    public static final RewriteRule POW_OF_ZERO_EXP = (expr, simp) -> {
        if (expr instanceof SymExpr.Pow(SymExpr a, SymExpr.Lit(long n)) && n == 0) {
            return Optional.of(SymExpr.lit(1));
        }
        if (expr instanceof SymExpr.Pow(SymExpr a, SymExpr.Frac(long n, long d)) && n == 0) {
            return Optional.of(SymExpr.lit(1));
        }
        return Optional.empty();
    };

    public static final RewriteRule SAME_BASE_POW_MERGE = (expr, simp) -> {
        if (expr instanceof SymExpr.Mul(SymExpr.Pow(SymExpr b1, SymExpr e1), SymExpr.Pow(SymExpr b2, SymExpr e2))
                && b1.equals(b2)) {
            return Optional.of(SymExpr.pow(b1, SymExpr.add(e1, e2)));
        }
        return Optional.empty();
    };

    public static final RewriteRule MUL_CANONICALIZE = (expr, simp) -> {
        if (expr instanceof SymExpr.Mul) {
            SymExpr normalized = CommutativeMul.normalize(expr);
            if (!normalized.equals(expr)) {
                return Optional.of(normalized);
            }
        }
        return Optional.empty();
    };

    public static final RewriteRule POW_LIT_INT_EXP = (expr, simp) -> {
        if (expr instanceof SymExpr.Pow(SymExpr.Lit(long base), SymExpr.Lit(long exp))
                && exp >= 0 && exp < 32) {
            long result = 1;
            for (int i = 0; i < exp; i++) {
                result *= base;
            }
            return Optional.of(SymExpr.lit(result));
        }
        return Optional.empty();
    };

    public static List<RewriteRule> all() {
        return List.of(
                FRAC_NORM,
                FRAC_ADD,
                LIT_TO_FRAC_FOR_ADD,
                SAME_BASE_POW_MERGE,
                MUL_CANONICALIZE,
                POW_OF_ONE_EXP,
                POW_OF_ZERO_EXP,
                POW_LIT_INT_EXP);
    }
}
