package sibarum.pontif.core.symbolic;

import java.util.List;
import java.util.Optional;

/**
 * Identity / short-circuit rewrite rules for Boolean {@link SymExpr.And} and
 * {@link SymExpr.Or}. Lets refinement predicates of the form
 * {@code (== self 0) || (== self 1)} reduce when one side is a {@code Bool}
 * literal (typically after substituting {@code Self} with a concrete value
 * and reducing the inner comparisons).
 */
public final class BooleanRules {

    private BooleanRules() {}

    /** {@code true && x → x} and {@code x && true → x}; {@code false && _ → false}. */
    public static final RewriteRule AND_IDENTITY = (expr, simp) -> {
        if (!(expr instanceof SymExpr.And(SymExpr l, SymExpr r))) {
            return Optional.empty();
        }
        if (l instanceof SymExpr.Bool lb) {
            return Optional.of(lb.value() ? r : SymExpr.bool(false));
        }
        if (r instanceof SymExpr.Bool rb) {
            return Optional.of(rb.value() ? l : SymExpr.bool(false));
        }
        return Optional.empty();
    };

    /** {@code false || x → x} and {@code x || false → x}; {@code true || _ → true}. */
    public static final RewriteRule OR_IDENTITY = (expr, simp) -> {
        if (!(expr instanceof SymExpr.Or(SymExpr l, SymExpr r))) {
            return Optional.empty();
        }
        if (l instanceof SymExpr.Bool lb) {
            return Optional.of(lb.value() ? SymExpr.bool(true) : r);
        }
        if (r instanceof SymExpr.Bool rb) {
            return Optional.of(rb.value() ? SymExpr.bool(true) : l);
        }
        return Optional.empty();
    };

    public static List<RewriteRule> all() {
        return List.of(AND_IDENTITY, OR_IDENTITY);
    }
}
