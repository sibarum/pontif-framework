package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.Sign;
import sibarum.pontif.core.symbolic.SignAnalysis;
import sibarum.pontif.core.symbolic.SymExpr;

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
     * integers? Tries domain-neutral {@link SignAnalysis} and
     * {@link Refinements} first, then the integer-strictness bridge.
     */
    static boolean discharge(List<SymExpr> hypotheses, SymExpr goal) {
        if (SignAnalysis.canDischarge(hypotheses, goal)) return true;
        if (Refinements.discharge(hypotheses, goal)) return true;
        return integerStrictness(hypotheses, goal);
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
