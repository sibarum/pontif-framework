package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;

import java.util.List;

/**
 * Domain router for obligation discharge. <b>The routing is the discreteness
 * boundary:</b> integer-domain obligations go to {@link IntegerDischarge}
 * (whose {@code BoundAnalysis} backend legitimately uses integer-strict cuts,
 * {@code >c ⟹ >=c+1}); {@code Decimal}-domain obligations go to
 * {@link DecimalDischarge}, which uses only dense-valid reasoning and never
 * reaches {@code BoundAnalysis}. That is what makes
 * {@code [Int:@>0] → [Int:@>=1]} provable while
 * {@code [Decimal:@>0] → [Decimal:@>=1]} is correctly rejected
 * ({@code 0.5} is the counterexample) — same shape, different domain, decided
 * by this routing alone.
 */
final class Discharge {

    private Discharge() {}

    /** Discharges {@code goal} from {@code hypotheses} in the domain of {@code resultSort}. */
    static boolean discharge(Sort resultSort, List<SymExpr> hypotheses, SymExpr goal) {
        return isDecimal(resultSort)
                ? DecimalDischarge.discharge(hypotheses, goal)
                : IntegerDischarge.discharge(hypotheses, goal);
    }

    static boolean isDecimal(Sort sort) {
        return "Decimal".equals(sort.name());
    }
}
