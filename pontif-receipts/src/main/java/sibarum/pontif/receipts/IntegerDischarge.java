package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.BoundAnalysis;

import java.util.List;

/**
 * Discharge for the receipt-graph issuer / notary, specialized to the
 * <b>integer</b> domain.
 *
 * <p>A thin named wrapper over {@link BoundAnalysis#discharge} — the
 * linear-bound + sign engine in {@code pontif-predicates} that decides
 * integer thresholds (including the cases {@code Sign}/{@code Refinements}
 * alone can't, like {@code [Int:@>1]}), the integer-strictness bridge
 * ({@code POSITIVE ⟹ >= 1}, {@code NEGATIVE ⟹ <= -1}), and reflexive
 * equality, all via interval-evaluation of the linear normal form. The
 * earlier OR-chain of sign / refinement / strictness backstops was
 * empirically shown to be fully subsumed by {@code BoundAnalysis} across
 * the full test suite and dropped.
 *
 * <p>The wrapper still earns its keep as a <i>soundness gate</i>: it
 * marks the call site as "integer-domain only." {@link BoundAnalysis}
 * itself uses {@link sibarum.pontif.core.symbolic.SignAnalysis} for
 * non-linear atoms, and {@code Sign} is calibrated for the rational
 * ({@link SymExpr.Frac}) domain of the algebra layer — where
 * {@code x > 0} genuinely does <em>not</em> imply {@code x >= 1}
 * (consider {@code 1/2}). The integer-strictness step inside
 * {@code BoundAnalysis}'s atom-bound extraction (treating
 * {@code > c} as {@code [c+1, ∞)}) is sound only because the receipt-
 * graph's refinement predicates compile {@link SymExpr.Frac}-free and
 * Pontif's sort domain is {@code Int}/{@code Bool} only.
 *
 * <p><b>Soundness gate:</b> if {@code Float} (or other non-integer)
 * refinements ever participate in the kernel, this wrapper must NOT be
 * used for them — it's the integer counterpart of why float refinements
 * were deferred in the first place.
 */
final class IntegerDischarge {

    private IntegerDischarge() {}

    /** Can {@code goal} be discharged from {@code hypotheses} over the integers? */
    static boolean discharge(List<SymExpr> hypotheses, SymExpr goal) {
        return BoundAnalysis.discharge(hypotheses, goal);
    }
}
