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

    public static List<RewriteRule> all() {
        return List.of(CMP_LIT_LIT);
    }
}
