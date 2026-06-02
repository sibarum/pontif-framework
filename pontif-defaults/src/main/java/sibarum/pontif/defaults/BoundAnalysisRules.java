package sibarum.pontif.defaults;

import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.predicates.BoundAnalysis;

import java.util.List;
import java.util.Optional;

/**
 * Linear-bound + sign discharge for the production simplifier — the
 * compile-time counterpart of {@code IntegerDischarge}'s reasoning in the
 * receipt-graph path.
 *
 * <p>{@link #BOUND_DISCHARGE} fires on a {@link SymExpr.Cmp} goal,
 * gathers hypotheses from {@code simp.context()}, and asks
 * {@link BoundAnalysis#discharge} to decide. Subsumes most of what
 * {@link sibarum.pontif.core.symbolic.HypothesisRules} can do plus the
 * threshold cases sign analysis can't —
 * e.g. {@code inc(x:[Int:@>=1]):[Int:@>1] -> x + 1} now passes at compile
 * time, where the sign-only rule would have returned Residual.
 *
 * <p><b>Soundness gate:</b> {@code BoundAnalysis}'s integer-strictness
 * extraction (treating {@code > c} as {@code [c+1, ∞)}) is sound only on
 * integer-domain values. To keep the rule safe in the presence of
 * {@link SymExpr.Frac} (the rational forms used by the algebra layer),
 * the rule skips when any {@code Frac} appears in the goal or in any
 * hypothesis. Pontif's user-facing language is integer/bool only, so
 * this skip path is exercised only by algebra-layer tests and never in
 * production code.
 */
public final class BoundAnalysisRules {

    private BoundAnalysisRules() {}

    public static final RewriteRule BOUND_DISCHARGE = (expr, simp) -> {
        if (!(expr instanceof SymExpr.Cmp)) {
            return Optional.empty();
        }
        if (containsFrac(expr)) {
            return Optional.empty();
        }
        for (SymExpr h : simp.context().hypotheses()) {
            if (containsFrac(h)) {
                return Optional.empty();
            }
        }
        if (BoundAnalysis.discharge(simp.context().hypotheses(), expr)) {
            return Optional.of(SymExpr.bool(true));
        }
        return Optional.empty();
    };

    public static List<RewriteRule> all() {
        return List.of(BOUND_DISCHARGE);
    }

    /**
     * True iff {@code expr} (or any subexpression) is a non-integer value
     * ({@link SymExpr.Frac} or {@link SymExpr.Dec}). The integer-strict bound
     * engine abstains when one is present — the soundness gate that keeps
     * rationals/decimals out of integer reasoning.
     */
    private static boolean containsFrac(SymExpr expr) {
        return switch (expr) {
            case SymExpr.Frac unused -> true;
            case SymExpr.Dec unused -> true;
            case SymExpr.Lit unused -> false;
            case SymExpr.Bool unused -> false;
            case SymExpr.Var unused -> false;
            case SymExpr.Self unused -> false;
            case SymExpr.Add a -> containsFrac(a.left()) || containsFrac(a.right());
            case SymExpr.Mul m -> containsFrac(m.left()) || containsFrac(m.right());
            case SymExpr.Pow p -> containsFrac(p.base()) || containsFrac(p.exponent());
            case SymExpr.Cmp c -> containsFrac(c.left()) || containsFrac(c.right());
            case SymExpr.And a -> containsFrac(a.left()) || containsFrac(a.right());
            case SymExpr.Or o -> containsFrac(o.left()) || containsFrac(o.right());
            case SymExpr.Lam l -> containsFrac(l.body());
            case SymExpr.App a -> containsFrac(a.fn()) || containsFrac(a.arg());
            case SymExpr.Case c -> {
                if (containsFrac(c.scrutinee())) yield true;
                for (SymExpr.CaseBranch b : c.branches()) {
                    if (containsFrac(b.result())) yield true;
                }
                yield false;
            }
            case SymExpr.Record r -> {
                for (SymExpr v : r.members().values()) {
                    if (containsFrac(v)) yield true;
                }
                yield false;
            }
            case SymExpr.FieldAccess fa -> containsFrac(fa.base());
        };
    }
}
