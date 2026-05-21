package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.demo.symbolic.ArithmeticRules;
import sibarum.pontif.demo.symbolic.TotalExpressionRules;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotalExpressionTest {

    private static final Simplifier TOTAL = new Simplifier(combinedRules());

    private static List<RewriteRule> combinedRules() {
        List<RewriteRule> all = new ArrayList<>();
        all.addAll(TotalExpressionRules.all());
        all.addAll(ArithmeticRules.all());
        return all;
    }

    private static final SymExpr HALF = SymExpr.frac(1, 2);

    private static final SymExpr EPSILON = SymExpr.pow(SymExpr.lit(0), HALF);   // 0^(1/2) — nilpotent
    private static final SymExpr I       = SymExpr.pow(SymExpr.lit(-1), HALF);  // (-1)^(1/2) — imaginary
    private static final SymExpr J       = SymExpr.pow(SymExpr.lit(1), HALF);   // 1^(1/2) — idempotent

    @Test
    void epsilonSquaredIsZero() {
        assertEquals(SymExpr.lit(0), TOTAL.simplify(SymExpr.mul(EPSILON, EPSILON)));
    }

    @Test
    void iSquaredIsNegativeOne() {
        assertEquals(SymExpr.lit(-1), TOTAL.simplify(SymExpr.mul(I, I)));
    }

    @Test
    void jSquaredIsOne() {
        assertEquals(SymExpr.lit(1), TOTAL.simplify(SymExpr.mul(J, J)));
    }

    @Test
    void generatorsStandalone_doNotCollapse() {
        assertEquals(J, TOTAL.simplify(J));
        assertEquals(EPSILON, TOTAL.simplify(EPSILON));
        assertEquals(I, TOTAL.simplify(I));
    }

    @Test
    void differentBasisProductStaysSymbolic_ij() {
        SymExpr ij = SymExpr.mul(I, J);
        SymExpr simplified = TOTAL.simplify(ij);
        assertNotEquals(SymExpr.lit(-1), simplified);
        assertNotEquals(SymExpr.lit(0),  simplified);
        assertNotEquals(SymExpr.lit(1),  simplified);
        String s = simplified.toString();
        assertTrue(s.contains("-1") && s.contains("1"),
                "ij should remain a product of (-1)^(1/2) and 1^(1/2); got: " + s);
    }

    @Test
    void differentBasisProductStaysSymbolic_epsilonI() {
        SymExpr simplified = TOTAL.simplify(SymExpr.mul(EPSILON, I));
        assertNotEquals(SymExpr.lit(0), simplified);
        assertNotEquals(SymExpr.lit(-1), simplified);
        assertNotEquals(EPSILON, simplified);
        assertNotEquals(I, simplified);
    }

    @Test
    void fracHalfPlusHalfIsOne() {
        assertEquals(SymExpr.lit(1), TOTAL.simplify(SymExpr.add(HALF, HALF)));
    }

    @Test
    void fracNormalizesToLitWhenDenomIsOne() {
        assertEquals(SymExpr.lit(3), TOTAL.simplify(SymExpr.frac(6, 2)));
    }

    @Test
    void powOfOneExponentCollapses() {
        assertEquals(SymExpr.lit(-1),
                TOTAL.simplify(SymExpr.pow(SymExpr.lit(-1), SymExpr.lit(1))));
    }

    @Test
    void powOfZeroExponentBecomesOne() {
        assertEquals(SymExpr.lit(1),
                TOTAL.simplify(SymExpr.pow(SymExpr.lit(-1), SymExpr.lit(0))));
    }

    @Test
    void powLitIntExpFolds() {
        assertEquals(SymExpr.lit(8),
                TOTAL.simplify(SymExpr.pow(SymExpr.lit(2), SymExpr.lit(3))));
    }

    // --- Commutative normalization tests ---

    @Test
    void commutativeReorder_jiBecomesij() {
        // J · I should canonicalize to I · J (I's base -1 sorts before J's base 1)
        SymExpr ji = SymExpr.mul(J, I);
        SymExpr expected = SymExpr.mul(I, J);
        assertEquals(expected, TOTAL.simplify(ji));
    }

    @Test
    void ijiCollapsesToMinusJ_leftAssociated() {
        // (i · j) · i  →  i² · j  →  -1 · j
        SymExpr iji = SymExpr.mul(SymExpr.mul(I, J), I);
        assertEquals(SymExpr.mul(SymExpr.lit(-1), J), TOTAL.simplify(iji));
    }

    @Test
    void ijiCollapsesToMinusJ_rightAssociated() {
        // i · (j · i)  →  i² · j  →  -1 · j
        SymExpr iji = SymExpr.mul(I, SymExpr.mul(J, I));
        assertEquals(SymExpr.mul(SymExpr.lit(-1), J), TOTAL.simplify(iji));
    }

    @Test
    void ijSquaredCollapsesToMinusOne() {
        // (i · j)² = (ij)(ij) = i²j² = (-1)(1) = -1
        SymExpr ij = SymExpr.mul(I, J);
        SymExpr ijSquared = SymExpr.mul(ij, ij);
        assertEquals(SymExpr.lit(-1), TOTAL.simplify(ijSquared));
    }

    @Test
    void epsilonSquaredTimesAnything_isZero() {
        // ε² · i  →  0 · i  →  0
        SymExpr expr = SymExpr.mul(SymExpr.mul(EPSILON, EPSILON), I);
        assertEquals(SymExpr.lit(0), TOTAL.simplify(expr));
    }

    @Test
    void allThreeGeneratorsTogether_staysSymbolicButCanonical() {
        // ε · i · j — three distinct bases, no merges, but should canonicalize the order
        SymExpr expr = SymExpr.mul(SymExpr.mul(EPSILON, I), J);
        SymExpr simplified = TOTAL.simplify(expr);
        // No collapse to a scalar
        assertNotEquals(SymExpr.lit(0), simplified);
        assertNotEquals(SymExpr.lit(-1), simplified);
        assertNotEquals(SymExpr.lit(1), simplified);
        // Same simplified form regardless of input ordering
        SymExpr otherOrder = SymExpr.mul(SymExpr.mul(J, EPSILON), I);
        assertEquals(simplified, TOTAL.simplify(otherOrder));
        SymExpr thirdOrder = SymExpr.mul(I, SymExpr.mul(J, EPSILON));
        assertEquals(simplified, TOTAL.simplify(thirdOrder));
    }

    @Test
    void fourFactorIIJJ_collapsesToMinusOne() {
        // i · i · j · j = i² · j² = -1 · 1 = -1
        SymExpr expr = SymExpr.mul(
                SymExpr.mul(I, I),
                SymExpr.mul(J, J));
        assertEquals(SymExpr.lit(-1), TOTAL.simplify(expr));
    }

    @Test
    void fourFactorMixedOrder_iJiJ_collapsesToMinusOne() {
        // i · j · i · j  same as (ij)² but written as a flat product
        SymExpr expr = SymExpr.mul(
                SymExpr.mul(SymExpr.mul(I, J), I),
                J);
        assertEquals(SymExpr.lit(-1), TOTAL.simplify(expr));
    }
}
