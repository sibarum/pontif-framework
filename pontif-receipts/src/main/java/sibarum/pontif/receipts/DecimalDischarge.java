package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.BoundAnalysis;

import java.util.List;

/**
 * Discharge for {@code Decimal}-domain obligations — a thin named wrapper over
 * the unified {@link BoundAnalysis} engine in {@link BoundAnalysis.Domain#DECIMAL}.
 *
 * <p>The engine is shared with {@link IntegerDischarge}; the domain argument is
 * the whole difference. In the Decimal domain the engine's quantization step is
 * the identity, so no integer-strict cut ({@code >c ⟹ >=c+1},
 * {@code POSITIVE ⟹ >=1}) is ever applied — {@code 0.5} witnesses {@code >0}
 * without {@code >=1}. The dense-valid reasoning this class used to spell out
 * (And/Or goal decomposition, ground constant comparison, single-hypothesis
 * order implication, and the sign lattice) is all subsumed by the shared
 * {@link RealInterval} arithmetic; what it additionally gains is linear-bound
 * reasoning — additive thresholds like {@code x>5 ∧ y>=0 ⟹ x+y>5} — which the
 * old order-implication-plus-sign backend could not derive.
 *
 * <p><b>Soundness gate:</b> the Int/Decimal routing lives in {@link Discharge};
 * this class must always pass {@link BoundAnalysis.Domain#DECIMAL}. The single
 * dense-invalid move (the integer grid) is quarantined inside
 * {@code BoundAnalysis.quantize} under a {@code Domain.INT} guard.
 */
final class DecimalDischarge {

    private DecimalDischarge() {}

    /** Can {@code goal} be discharged from {@code hypotheses} over the decimals? */
    static boolean discharge(List<SymExpr> hypotheses, SymExpr goal) {
        return BoundAnalysis.discharge(BoundAnalysis.Domain.DECIMAL, hypotheses, goal);
    }
}
