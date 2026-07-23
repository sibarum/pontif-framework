package sibarum.pontif.predicates;

import sibarum.pontif.core.symbolic.BoundAnalysis;
import sibarum.pontif.core.symbolic.RealInterval;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.SymExpr;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The domain axis of the unified engine. The same arithmetic serves both
 * domains; the only difference is the grid. These tests pin the contrast
 * pairs — cases that must diverge between {@link BoundAnalysis.Domain#INT} and
 * {@link BoundAnalysis.Domain#DECIMAL} — and the additive linear-bound
 * reasoning the Decimal domain gained (which the old order-implication + sign
 * backend could not do).
 */
class BoundAnalysisDomainTest {

    private static final BoundAnalysis.Domain INT = BoundAnalysis.Domain.INT;
    private static final BoundAnalysis.Domain DEC = BoundAnalysis.Domain.DECIMAL;

    private static SymExpr v(String n) { return SymExpr.var(n); }
    private static SymExpr add(SymExpr a, SymExpr b) { return SymExpr.add(a, b); }
    private static SymExpr dec(String s) { return new SymExpr.Dec(new BigDecimal(s)); }
    private static SymExpr cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r) { return SymExpr.cmp(l, op, r); }
    private static SymExpr gtD(SymExpr l, String n) { return cmp(l, SymExpr.CmpOp.GT, dec(n)); }
    private static SymExpr geD(SymExpr l, String n) { return cmp(l, SymExpr.CmpOp.GE, dec(n)); }
    private static SymExpr self() { return new SymExpr.Self(); }

    // --- the density contrast: >0 ⟹ >=1 holds for Int, not Decimal ---------

    @Test
    void positiveImpliesAtLeastOne_onlyForIntegers() {
        List<SymExpr> hyp = List.of(cmp(self(), SymExpr.CmpOp.GT, dec("0")));
        SymExpr goal = cmp(self(), SymExpr.CmpOp.GE, dec("1"));
        assertTrue(BoundAnalysis.discharge(INT, hyp, goal), "for integers, >0 ⟹ >=1");
        assertFalse(BoundAnalysis.discharge(DEC, hyp, goal), "0.5 witnesses >0 without >=1");
    }

    // --- additive threshold reasoning, now available in the dense domain ---

    @Test
    void decimalSumClearsAdditiveThreshold() {
        // x > 5 ∧ y >= 0  ⟹  x + y > 5     (the x + this.x report, distilled)
        List<SymExpr> hyp = List.of(gtD(v("x"), "5"), geD(v("y"), "0"));
        SymExpr goal = cmp(add(v("x"), v("y")), SymExpr.CmpOp.GT, dec("5"));
        assertTrue(BoundAnalysis.discharge(DEC, hyp, goal));
    }

    @Test
    void decimalSumOfLowerBoundsAddsThem() {
        // x >= 2 ∧ y >= 3  ⟹  x + y >= 5
        List<SymExpr> hyp = List.of(geD(v("x"), "2"), geD(v("y"), "3"));
        SymExpr goal = cmp(add(v("x"), v("y")), SymExpr.CmpOp.GE, dec("5"));
        assertTrue(BoundAnalysis.discharge(DEC, hyp, goal));
    }

    // --- soundness: the dense domain must NOT fabricate magnitude ----------

    @Test
    void decimalPositivesDoNotClearANonzeroThreshold() {
        // x > 0 ∧ y > 0  does NOT prove  x + y > 5  (sign gives only > 0)
        List<SymExpr> hyp = List.of(gtD(v("x"), "0"), gtD(v("y"), "0"));
        SymExpr goal = cmp(add(v("x"), v("y")), SymExpr.CmpOp.GT, dec("5"));
        assertFalse(BoundAnalysis.discharge(DEC, hyp, goal));
    }

    @Test
    void decimalStrictSumIsStrictAtTheBoundary() {
        // x > 5 ∧ y > 0  ⟹  x + y > 5  (both open, the infimum 5 is not attained)
        List<SymExpr> hyp = List.of(gtD(v("x"), "5"), gtD(v("y"), "0"));
        SymExpr goal = cmp(add(v("x"), v("y")), SymExpr.CmpOp.GT, dec("5"));
        assertTrue(BoundAnalysis.discharge(DEC, hyp, goal));
        // …but the closed goal >= at a strictly-open infimum still holds too.
        SymExpr geGoal = cmp(add(v("x"), v("y")), SymExpr.CmpOp.GE, dec("5"));
        assertTrue(BoundAnalysis.discharge(DEC, hyp, geGoal));
    }

    @Test
    void decimalSubsumesSingleHypothesisOrderImplication() {
        // x > 5  ⟹  x > 3   (what the old Refinements.implies covered)
        List<SymExpr> hyp = List.of(gtD(v("x"), "5"));
        assertTrue(BoundAnalysis.discharge(DEC, hyp, cmp(v("x"), SymExpr.CmpOp.GT, dec("3"))));
    }

    // --- Int parity: the two-arg entry point still means INT ----------------

    @Test
    void twoArgEntryPointIsInteger() {
        List<SymExpr> hyp = List.of(cmp(self(), SymExpr.CmpOp.GT, SymExpr.lit(0)));
        SymExpr goal = cmp(self(), SymExpr.CmpOp.GE, SymExpr.lit(1));
        assertTrue(BoundAnalysis.discharge(hyp, goal));
    }
}
