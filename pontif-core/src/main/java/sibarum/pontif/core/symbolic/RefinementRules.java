package sibarum.pontif.core.symbolic;

import java.util.List;
import java.util.Optional;

public final class RefinementRules {

    private RefinementRules() {}

    public static final RewriteRule CMP_LIT_LIT = (expr, simp) -> {
        if (expr instanceof SymExpr.Cmp(SymExpr.Lit l, SymExpr.CmpOp op, SymExpr.Lit r)) {
            boolean truth = switch (op) {
                case LT -> l.value() < r.value();
                case LE -> l.value() <= r.value();
                case GT -> l.value() > r.value();
                case GE -> l.value() >= r.value();
                case EQ -> l.value() == r.value();
                case NE -> l.value() != r.value();
            };
            return Optional.of(SymExpr.bool(truth));
        }
        return Optional.empty();
    };

    /**
     * Bool counterpart to {@link #CMP_LIT_LIT}: folds {@code Bool(a) == Bool(b)}
     * and {@code Bool(a) != Bool(b)} to a Bool literal so {@code Bool} match
     * arms (e.g. {@code [@==true]}) can be decided at runtime after substituting
     * {@code Self} with the scrutinee value. Ordering ops on Bool aren't part of
     * Pontif's surface semantics — leave them residual rather than impose an
     * arbitrary boolean ordering.
     */
    public static final RewriteRule CMP_BOOL_BOOL = (expr, simp) -> {
        if (expr instanceof SymExpr.Cmp(SymExpr.Bool l, SymExpr.CmpOp op, SymExpr.Bool r)) {
            return switch (op) {
                case EQ -> Optional.of(SymExpr.bool(l.value() == r.value()));
                case NE -> Optional.of(SymExpr.bool(l.value() != r.value()));
                case LT, LE, GT, GE -> Optional.empty();
            };
        }
        return Optional.empty();
    };

    public static List<RewriteRule> all() {
        return List.of(CMP_LIT_LIT, CMP_BOOL_BOOL);
    }
}
