package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;

import java.util.ArrayList;
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
        // Flatten conjunctions and case-split on a disjunctive hypothesis: the
        // goal holds under {H, A ∨ B} iff it holds under both {H, A} and {H, B}.
        // A `_` arm's complement region may be discontiguous (e.g. [1,4] ∪
        // [6,∞), or a multi-variable gap), arriving here as an Or; a disjunct
        // that contradicts the other hypotheses makes its case vacuous
        // (BoundAnalysis returns true on an empty range). Sound: an Or weakens
        // the premise, so proving every disjunct proves the whole.
        List<SymExpr> flat = new ArrayList<>();
        for (SymExpr h : hypotheses) flattenConjunction(h, flat);
        for (int i = 0; i < flat.size(); i++) {
            if (flat.get(i) instanceof SymExpr.Or(SymExpr l, SymExpr r)) {
                return discharge(resultSort, withReplaced(flat, i, l), goal)
                        && discharge(resultSort, withReplaced(flat, i, r), goal);
            }
        }
        return isDecimal(resultSort)
                ? DecimalDischarge.discharge(flat, goal)
                : IntegerDischarge.discharge(flat, goal);
    }

    /** Splits top-level {@code And}s into separate hypotheses so nested Ors surface. */
    private static void flattenConjunction(SymExpr e, List<SymExpr> out) {
        if (e instanceof SymExpr.And(SymExpr l, SymExpr r)) {
            flattenConjunction(l, out);
            flattenConjunction(r, out);
        } else {
            out.add(e);
        }
    }

    private static List<SymExpr> withReplaced(List<SymExpr> hyps, int idx, SymExpr with) {
        List<SymExpr> copy = new ArrayList<>(hyps);
        copy.set(idx, with);
        return copy;
    }

    static boolean isDecimal(Sort sort) {
        return "Decimal".equals(sort.name());
    }
}
