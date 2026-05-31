package sibarum.pontif.predicates;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.SymExpr;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hybrid linear-bound + sign engine. The headline it adds over
 * {@link sibarum.pontif.core.symbolic.SignAnalysis} is integer
 * <em>thresholds</em> — {@code [Int:@>1]} and friends — which sign
 * analysis can't express. Every discharge must be sound: the negatives
 * assert the engine refuses the cases it genuinely can't prove.
 */
class BoundAnalysisTest {

    private static SymExpr v(String name) { return SymExpr.var(name); }
    private static SymExpr lit(long n) { return SymExpr.lit(n); }
    private static SymExpr add(SymExpr a, SymExpr b) { return SymExpr.add(a, b); }
    private static SymExpr mul(SymExpr a, SymExpr b) { return SymExpr.mul(a, b); }
    private static SymExpr cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r) {
        return SymExpr.cmp(l, op, r);
    }
    private static SymExpr ge(SymExpr l, long n) { return cmp(l, SymExpr.CmpOp.GE, lit(n)); }
    private static SymExpr gt(SymExpr l, long n) { return cmp(l, SymExpr.CmpOp.GT, lit(n)); }

    // --- bound() ------------------------------------------------------------

    @Test
    void boundOfLiteralIsAPoint() {
        assertEquals(Interval.point(7), BoundAnalysis.bound(lit(7), List.of()));
    }

    @Test
    void boundOfUnconstrainedVarIsAll() {
        assertEquals(Interval.all(), BoundAnalysis.bound(v("x"), List.of()));
    }

    @Test
    void boundOfVarUnderLowerBoundHyp() {
        // x with x >= 1  →  [1, ∞)
        assertEquals(Interval.atLeast(1), BoundAnalysis.bound(v("x"), List.of(ge(v("x"), 1))));
    }

    @Test
    void boundOfSumShiftsTheLowerBound() {
        // x + 1 with x >= 1  →  [2, ∞)
        Interval iv = BoundAnalysis.bound(add(v("x"), lit(1)), List.of(ge(v("x"), 1)));
        assertEquals(Interval.atLeast(2), iv);
    }

    @Test
    void boundOfScaledVar() {
        // 2x with x >= 1  →  [2, ∞)
        Interval iv = BoundAnalysis.bound(mul(lit(2), v("x")), List.of(ge(v("x"), 1)));
        assertEquals(Interval.atLeast(2), iv);
    }

    @Test
    void boundOfProductFromSign() {
        // n * r with n > 0, r >= 1  → sign POSITIVE → [1, ∞)
        Interval iv = BoundAnalysis.bound(
                mul(v("n"), v("r")), List.of(gt(v("n"), 0), ge(v("r"), 1)));
        assertEquals(Interval.atLeast(1), iv);
    }

    @Test
    void boundOfSquareIsNonNegative() {
        // x * x with no hypotheses → NON_NEGATIVE → [0, ∞)
        Interval iv = BoundAnalysis.bound(mul(v("x"), v("x")), List.of());
        assertEquals(Interval.atLeast(0), iv);
    }

    @Test
    void boundFromRangeHypothesisFlattensConjunction() {
        // x with (x >= 1 ∧ x <= 4) — one And hypothesis → [1, 4]
        SymExpr range = SymExpr.and(ge(v("x"), 1), cmp(v("x"), SymExpr.CmpOp.LE, lit(4)));
        assertEquals(new Interval(1, 4), BoundAnalysis.bound(v("x"), List.of(range)));
    }

    @Test
    void dischargesUpperBoundFromRangeHypothesis() {
        // x + 1 <= 5  from  (x >= 1 ∧ x <= 4)
        SymExpr range = SymExpr.and(ge(v("x"), 1), cmp(v("x"), SymExpr.CmpOp.LE, lit(4)));
        SymExpr goal = cmp(add(v("x"), lit(1)), SymExpr.CmpOp.LE, lit(5));
        assertTrue(BoundAnalysis.discharge(List.of(range), goal));
    }

    // --- discharge(): the threshold headline --------------------------------

    @Test
    void dischargesThresholdSignAnalysisCannot() {
        // x + 1 > 1  from  x >= 1   (the [Int:@>1] case sign analysis misses)
        assertTrue(BoundAnalysis.discharge(
                List.of(ge(v("x"), 1)), gt(add(v("x"), lit(1)), 1)));
    }

    @Test
    void dischargesLinearCombination() {
        // 2x + 3 >= 5  from  x >= 1
        SymExpr goal = cmp(add(mul(lit(2), v("x")), lit(3)), SymExpr.CmpOp.GE, lit(5));
        assertTrue(BoundAnalysis.discharge(List.of(ge(v("x"), 1)), goal));
    }

    @Test
    void dischargesFactorialRecursiveStep() {
        // n * r >= 1  from  n > 0, r >= 1  (the recursive arm's obligation)
        SymExpr goal = cmp(mul(v("n"), v("r")), SymExpr.CmpOp.GE, lit(1));
        assertTrue(BoundAnalysis.discharge(List.of(gt(v("n"), 0), ge(v("r"), 1)), goal));
    }

    @Test
    void dischargesSquareIsNonNegative() {
        // x * x >= 0  with no hypotheses
        SymExpr goal = cmp(mul(v("x"), v("x")), SymExpr.CmpOp.GE, lit(0));
        assertTrue(BoundAnalysis.discharge(List.of(), goal));
    }

    @Test
    void dischargesReflexiveEqualityViaCancellation() {
        // y + 1 == y + 1  → atoms cancel → [0, 0] → EQ
        SymExpr e = add(v("y"), lit(1));
        assertTrue(BoundAnalysis.discharge(List.of(), cmp(e, SymExpr.CmpOp.EQ, e)));
    }

    @Test
    void contradictoryHypothesesDischargeAnything() {
        // x >= 5 ∧ x <= 0 is empty → any goal about x discharges vacuously
        List<SymExpr> contradictory =
                List.of(ge(v("x"), 5), cmp(v("x"), SymExpr.CmpOp.LE, lit(0)));
        assertTrue(BoundAnalysis.discharge(contradictory, gt(v("x"), 1000)));
    }

    // --- discharge(): product magnitude (interval multiplication) -----------

    @Test
    void dischargesProductMagnitude() {
        // x * y >= 6  from  x >= 2, y >= 3  — sign alone gives only >= 1;
        // interval-multiplying the factors gives [6, ∞).
        SymExpr goal = cmp(mul(v("x"), v("y")), SymExpr.CmpOp.GE, lit(6));
        assertTrue(BoundAnalysis.discharge(List.of(ge(v("x"), 2), ge(v("y"), 3)), goal));
    }

    @Test
    void dischargesSparsePolynomialUpperRegion() {
        // (x-3)*(x+5) >= -16  from  x >= 3  — isSparse branch A.
        // (x-3) ∈ [0,∞), (x+5) ∈ [8,∞), product ∈ [0,∞) >= -16.
        SymExpr poly = mul(add(v("x"), lit(-3)), add(v("x"), lit(5)));
        SymExpr goal = cmp(poly, SymExpr.CmpOp.GE, lit(-16));
        assertTrue(BoundAnalysis.discharge(List.of(ge(v("x"), 3)), goal));
    }

    @Test
    void dischargesSparsePolynomialLowerRegion() {
        // (x-3)*(x+5) >= -16  from  x <= -6  — isSparse branch C.
        // (x-3) ∈ (-∞,-9], (x+5) ∈ (-∞,-1], product (two negatives) ∈ [9,∞).
        SymExpr poly = mul(add(v("x"), lit(-3)), add(v("x"), lit(5)));
        SymExpr goal = cmp(poly, SymExpr.CmpOp.GE, lit(-16));
        assertTrue(BoundAnalysis.discharge(
                List.of(cmp(v("x"), SymExpr.CmpOp.LE, lit(-6))), goal));
    }

    @Test
    void dischargesBoundedProductUpperBound() {
        // x * y <= 20  from  (1<=x<=4), (2<=y<=5)  → [1,4]·[2,5] = [2,20]
        SymExpr xr = SymExpr.and(ge(v("x"), 1), cmp(v("x"), SymExpr.CmpOp.LE, lit(4)));
        SymExpr yr = SymExpr.and(ge(v("y"), 2), cmp(v("y"), SymExpr.CmpOp.LE, lit(5)));
        SymExpr goal = cmp(mul(v("x"), v("y")), SymExpr.CmpOp.LE, lit(20));
        assertTrue(BoundAnalysis.discharge(List.of(xr, yr), goal));
    }

    // --- discharge(): soundness (must refuse) -------------------------------

    @Test
    void refusesThresholdWithoutSupportingHypothesis() {
        // y + 1 > 1 with nothing known about y — y could be -5
        assertFalse(BoundAnalysis.discharge(List.of(), gt(add(v("y"), lit(1)), 1)));
    }

    @Test
    void refusesStrictPositivityOfSquareFromNonNegative() {
        // x * x > 0 from x >= 0 — x could be 0
        SymExpr goal = cmp(mul(v("x"), v("x")), SymExpr.CmpOp.GT, lit(0));
        assertFalse(BoundAnalysis.discharge(List.of(ge(v("x"), 0)), goal));
    }

    @Test
    void refusesSquareThreshold() {
        // x * x >= 1 with no hypotheses — x could be 0 (the doc's false-ish case)
        SymExpr goal = cmp(mul(v("x"), v("x")), SymExpr.CmpOp.GE, lit(1));
        assertFalse(BoundAnalysis.discharge(List.of(), goal));
    }

    @Test
    void refusesSparsePolynomialMiddleRegionWithoutSplit() {
        // (x-3)*(x+5) >= -16  from  -5 <= x <= 2  — isSparse branch B.
        // The true minimum (-16, at x=-1) holds, but interval mult over the
        // whole region gives [-8,-1]·[0,7] = [-56,0], lower bound -56 < -16.
        // The engine must REFUSE here — this is precisely the case that needs
        // a case-split (the receipt-graph-refinement slice), not a wider bound.
        SymExpr poly = mul(add(v("x"), lit(-3)), add(v("x"), lit(5)));
        SymExpr goal = cmp(poly, SymExpr.CmpOp.GE, lit(-16));
        List<SymExpr> region = List.of(ge(v("x"), -5), cmp(v("x"), SymExpr.CmpOp.LE, lit(2)));
        assertFalse(BoundAnalysis.discharge(region, goal));
    }

    @Test
    void refusesMultiAtomHypothesisConstraint() {
        // x + y > 0 bounds neither x nor y alone; x > 0 isn't derivable
        assertFalse(BoundAnalysis.discharge(
                List.of(gt(add(v("x"), v("y")), 0)), gt(v("x"), 0)));
    }

    @Test
    void nonComparisonGoalIsNotDecided() {
        assertFalse(BoundAnalysis.discharge(List.of(), SymExpr.bool(true)));
    }
}
