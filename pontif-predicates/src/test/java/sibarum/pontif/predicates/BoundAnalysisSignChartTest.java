package sibarum.pontif.predicates;

import sibarum.pontif.core.symbolic.BoundAnalysis;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.SymExpr;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The internal sign-chart case-split: the sign of a product of linear factors
 * over a single integer variable, decided by splitting the variable at the
 * factors' roots into an exhaustive integer cover — no user-supplied proof.
 * The negatives are the point: a product that is genuinely negative somewhere
 * must NOT discharge (a violating value lives in some cell, which refuses).
 */
class BoundAnalysisSignChartTest {

    private static SymExpr v(String name) { return SymExpr.var(name); }
    private static SymExpr lit(long n) { return SymExpr.lit(n); }
    private static SymExpr mul(SymExpr a, SymExpr b) { return SymExpr.mul(a, b); }
    private static SymExpr add(SymExpr a, SymExpr b) { return SymExpr.add(a, b); }
    private static SymExpr ge(SymExpr l, long n) { return SymExpr.cmp(l, SymExpr.CmpOp.GE, lit(n)); }

    /** x*(x-1) — consecutive integers — is always >= 0. The parabola headline. */
    @Test
    void productOfConsecutiveIntegers_isNonNegative() {
        SymExpr parabola = mul(v("x"), add(v("x"), lit(-1)));
        assertTrue(BoundAnalysis.discharge(List.of(), ge(parabola, 0)),
                "x*(x-1) >= 0 should discharge via the sign-chart split");
    }

    /** x*(x+1) — also consecutive, roots at -1 and 0 — is always >= 0. */
    @Test
    void productAroundNegativeRoot_isNonNegative() {
        SymExpr p = mul(v("x"), add(v("x"), lit(1)));
        assertTrue(BoundAnalysis.discharge(List.of(), ge(p, 0)),
                "x*(x+1) >= 0 should discharge");
    }

    /** x*(x-1) hits 0 at x=0 and x=1, so >= 1 is FALSE — must not discharge. */
    @Test
    void productConsecutive_isNotAtLeastOne() {
        SymExpr parabola = mul(v("x"), add(v("x"), lit(-1)));
        assertFalse(BoundAnalysis.discharge(List.of(), ge(parabola, 1)),
                "x*(x-1) >= 1 is false (0 at x=0,1) — must not discharge");
    }

    /** x*(x-2) = -1 at x=1 (the gap between the roots), so >= 0 is FALSE. */
    @Test
    void productWithInteriorNegative_doesNotDischarge() {
        SymExpr p = mul(v("x"), add(v("x"), lit(-2)));
        assertFalse(BoundAnalysis.discharge(List.of(), ge(p, 0)),
                "x*(x-2) >= 0 is false (x=1 gives -1) — must not discharge");
    }
}
