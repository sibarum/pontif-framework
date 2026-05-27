package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.Sign;
import sibarum.pontif.core.symbolic.SignAnalysis;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.predicates.BoundAnalysis;

import java.util.List;

/**
 * Discharge for the receipt-graph issuer / notary, specialized to the
 * <b>integer</b> domain.
 *
 * <p>Layers an <i>integer-strictness bridge</i> over {@link SignAnalysis}:
 * for integers, a strictly-positive value is {@code >= 1} and a
 * strictly-negative value is {@code <= -1}. {@link Sign#satisfies} can't
 * make that leap on its own because {@code Sign} is a general symbolic
 * tool that also serves the rational ({@link SymExpr.Frac}) domain of the
 * algebra layer — where {@code x > 0} genuinely does <em>not</em> imply
 * {@code x >= 1} (consider {@code 1/2}). So the bridge lives <em>here</em>,
 * in the refinement issuer, where every value is provably an integer
 * (refinement predicates compile Frac-free and Pontif's sort domain is
 * {@code Int}/{@code Bool} only). {@code Sign} stays domain-neutral.
 *
 * <p><b>Soundness gate:</b> this is sound only while the refinement domain
 * is integer-only. If {@code Float} refinements ever participate in the
 * kernel, this bridge must NOT be applied to them — it's the integer
 * counterpart of why float refinements were deferred in the first place.
 */
final class IntegerDischarge {

    private IntegerDischarge() {}

    /**
     * Can {@code goal} be discharged from {@code hypotheses} over the
     * integers? Tries the {@link BoundAnalysis linear-bound + sign} engine
     * first — it decides integer thresholds like {@code [Int:@>1]} that
     * sign analysis alone can't, and subsumes both the reflexive-equality
     * case and the integer-strictness bridge below. The remaining checks
     * stay as sound backstops: each is sound on its own, so OR-ing them can
     * only add discharges, never flip a verdict either reaches.
     */
    static boolean discharge(List<SymExpr> hypotheses, SymExpr goal) {
        if (isReflexiveEquality(goal)) return true;
        if (BoundAnalysis.discharge(hypotheses, goal)) return true;
        if (SignAnalysis.canDischarge(hypotheses, goal)) return true;
        if (Refinements.discharge(hypotheses, goal)) return true;
        return integerStrictness(hypotheses, goal);
    }

    /**
     * {@code expr == expr} is true for any value. This discharges the
     * tautology a value-pinning return creates: a spec-only
     * {@code :[Int:@==y+1]} synthesizes body {@code y+1}, so the obligation
     * {@code r_0 == y_0+1} reduces (after substituting the definition) to
     * {@code y_0+1 == y_0+1}.
     */
    private static boolean isReflexiveEquality(SymExpr goal) {
        return goal instanceof SymExpr.Cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r)
                && op == SymExpr.CmpOp.EQ
                && l.equals(r);
    }

    /**
     * The bridge proper. For an integer subject:
     * <ul>
     *   <li>{@code POSITIVE} (i.e. {@code > 0}) satisfies {@code >= b}
     *       whenever {@code b <= 1} — the {@code b == 1} case is the one
     *       {@link Sign#satisfies} misses (it only allows {@code b <= 0}).
     *   <li>{@code NEGATIVE} (i.e. {@code < 0}) satisfies {@code <= b}
     *       whenever {@code b >= -1} — the {@code b == -1} case.
     * </ul>
     * All other operators are already tight in {@link Sign#satisfies} for
     * integers, so the bridge adds nothing there.
     */
    private static boolean integerStrictness(List<SymExpr> hypotheses, SymExpr goal) {
        if (!(goal instanceof SymExpr.Cmp(SymExpr subject, SymExpr.CmpOp op, SymExpr bound))) {
            return false;
        }
        Long b = asLong(bound);
        if (b == null) return false;
        Sign s = SignAnalysis.computeSign(subject, hypotheses);
        return switch (op) {
            case GE -> s == Sign.POSITIVE && b <= 1;
            case LE -> s == Sign.NEGATIVE && b >= -1;
            default -> false;
        };
    }

    private static Long asLong(SymExpr e) {
        if (e instanceof SymExpr.Lit l) return l.value();
        if (e instanceof SymExpr.Frac f && f.denom() == 1) return f.num();
        return null;
    }
}
