package sibarum.pontif.core.symbolic;

import java.util.List;
import java.util.Optional;

public final class ArithmeticRules {

    private ArithmeticRules() {}

    public static final RewriteRule X_PLUS_ZERO = (expr, simp) -> {
        if (expr instanceof SymExpr.Add(SymExpr l, SymExpr.Lit r) && r.value() == 0L) {
            return Optional.of(l);
        }
        if (expr instanceof SymExpr.Add(SymExpr.Lit l, SymExpr r) && l.value() == 0L) {
            return Optional.of(r);
        }
        return Optional.empty();
    };

    public static final RewriteRule X_TIMES_ONE = (expr, simp) -> {
        if (expr instanceof SymExpr.Mul(SymExpr l, SymExpr.Lit r) && r.value() == 1L) {
            return Optional.of(l);
        }
        if (expr instanceof SymExpr.Mul(SymExpr.Lit l, SymExpr r) && l.value() == 1L) {
            return Optional.of(r);
        }
        return Optional.empty();
    };

    public static final RewriteRule X_TIMES_ZERO = (expr, simp) -> {
        if (expr instanceof SymExpr.Mul(SymExpr l, SymExpr.Lit r) && r.value() == 0L) {
            return Optional.of(SymExpr.lit(0));
        }
        if (expr instanceof SymExpr.Mul(SymExpr.Lit l, SymExpr r) && l.value() == 0L) {
            return Optional.of(SymExpr.lit(0));
        }
        return Optional.empty();
    };

    public static final RewriteRule CONSTANT_FOLD_ADD = (expr, simp) -> {
        if (expr instanceof SymExpr.Add(SymExpr.Lit l, SymExpr.Lit r)) {
            return Optional.of(SymExpr.lit(l.value() + r.value()));
        }
        return Optional.empty();
    };

    public static final RewriteRule CONSTANT_FOLD_MUL = (expr, simp) -> {
        if (expr instanceof SymExpr.Mul(SymExpr.Lit l, SymExpr.Lit r)) {
            return Optional.of(SymExpr.lit(l.value() * r.value()));
        }
        return Optional.empty();
    };

    public static final RewriteRule X_PLUS_X_TO_TWO_X = (expr, simp) -> {
        if (expr instanceof SymExpr.Add(SymExpr.Var l, SymExpr.Var r) && l.name().equals(r.name())) {
            return Optional.of(SymExpr.mul(SymExpr.lit(2), l));
        }
        return Optional.empty();
    };

    public static List<RewriteRule> all() {
        return List.of(
                X_PLUS_ZERO,
                X_TIMES_ONE,
                X_TIMES_ZERO,
                CONSTANT_FOLD_ADD,
                CONSTANT_FOLD_MUL,
                X_PLUS_X_TO_TWO_X);
    }
}
