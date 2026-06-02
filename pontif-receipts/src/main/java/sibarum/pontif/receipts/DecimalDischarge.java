package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.SymExpr;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Discharge for {@code Decimal}-domain obligations — the dense counterpart of
 * {@link IntegerDischarge}, covering Decimal's three narrows (sign, range,
 * equality-up-to-precision).
 *
 * <p>Uses only <b>dense-valid</b> reasoning: And/Or goal decomposition, ground
 * evaluation of constant comparisons ({@code compareTo}-based, so
 * {@code 2.0 == 2.00}), and {@link Refinements#discharge} — order implication
 * over BigDecimal bounds plus the domain-neutral sign lattice.
 *
 * <p><b>Soundness gate (the inverse of {@link IntegerDischarge}'s):</b> this
 * must NEVER call {@code BoundAnalysis} — its integer-strict cuts
 * ({@code >c ⟹ >=c+1}, {@code POSITIVE ⟹ >=1}) are exactly what is false in a
 * dense domain ({@code 0.5} witnesses {@code >0} without {@code >=1}). The
 * Int/Decimal routing in {@link Discharge} is what keeps each domain's facts
 * on its own side.
 */
final class DecimalDischarge {

    private DecimalDischarge() {}

    /** Can {@code goal} be discharged from {@code hypotheses} over the decimals? */
    static boolean discharge(List<SymExpr> hypotheses, SymExpr goal) {
        List<SymExpr> flat = new ArrayList<>();
        for (SymExpr h : hypotheses) {
            flatten(h, flat);
        }
        return dischargeGoal(flat, goal);
    }

    /** Range hypotheses arrive as conjunctions ({@code @>=lo & @<=hi}) — expose the conjuncts. */
    private static void flatten(SymExpr h, List<SymExpr> out) {
        if (h instanceof SymExpr.And(SymExpr l, SymExpr r)) {
            flatten(l, out);
            flatten(r, out);
        } else {
            out.add(h);
        }
    }

    private static boolean dischargeGoal(List<SymExpr> hyps, SymExpr goal) {
        if (goal instanceof SymExpr.Bool b) {
            return b.value();
        }
        if (goal instanceof SymExpr.And(SymExpr l, SymExpr r)) {
            return dischargeGoal(hyps, l) && dischargeGoal(hyps, r);
        }
        if (goal instanceof SymExpr.Or(SymExpr l, SymExpr r)) {
            return dischargeGoal(hyps, l) || dischargeGoal(hyps, r);
        }
        Boolean ground = evalGround(goal);
        if (ground != null) {
            return ground;
        }
        return Refinements.discharge(hyps, goal);
    }

    /** Evaluates a comparison whose sides are both numeric constants; null if not ground. */
    private static Boolean evalGround(SymExpr goal) {
        if (!(goal instanceof SymExpr.Cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r))) {
            return null;
        }
        BigDecimal a = asNumeric(l);
        BigDecimal b = asNumeric(r);
        if (a == null || b == null) {
            return null;
        }
        int c = a.compareTo(b);
        return switch (op) {
            case LT -> c < 0;
            case LE -> c <= 0;
            case GT -> c > 0;
            case GE -> c >= 0;
            case EQ -> c == 0;
            case NE -> c != 0;
        };
    }

    private static BigDecimal asNumeric(SymExpr e) {
        if (e instanceof SymExpr.Dec d) return d.value();
        if (e instanceof SymExpr.Lit l) return BigDecimal.valueOf(l.value());
        return null;
    }
}
