package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.Force;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.Substitute;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.UnresolvedSymbolException;
import sibarum.pontif.demo.symbolic.ArithmeticRules;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolicExplorationTest {

    private static final Simplifier ARITH = new Simplifier(ArithmeticRules.all());

    @Test
    void constantFolds() {
        assertEquals(
                SymExpr.lit(5),
                ARITH.simplify(SymExpr.add(SymExpr.lit(2), SymExpr.lit(3))));
    }

    @Test
    void identityEliminates() {
        assertEquals(SymExpr.var("x"),
                ARITH.simplify(SymExpr.add(SymExpr.var("x"), SymExpr.lit(0))));
        assertEquals(SymExpr.var("x"),
                ARITH.simplify(SymExpr.add(SymExpr.lit(0), SymExpr.var("x"))));
    }

    @Test
    void absorptionFires() {
        assertEquals(SymExpr.lit(0),
                ARITH.simplify(SymExpr.mul(SymExpr.var("x"), SymExpr.lit(0))));
        assertEquals(SymExpr.lit(0),
                ARITH.simplify(SymExpr.mul(SymExpr.lit(0), SymExpr.var("x"))));
    }

    @Test
    void selfCoalesces() {
        assertEquals(
                SymExpr.mul(SymExpr.lit(2), SymExpr.var("x")),
                ARITH.simplify(SymExpr.add(SymExpr.var("x"), SymExpr.var("x"))));
    }

    @Test
    void chainsToFixedPoint() {
        SymExpr expr = SymExpr.add(
                SymExpr.var("x"),
                SymExpr.add(
                        SymExpr.lit(0),
                        SymExpr.mul(SymExpr.lit(1), SymExpr.var("x"))));
        assertEquals(
                SymExpr.mul(SymExpr.lit(2), SymExpr.var("x")),
                ARITH.simplify(expr));
    }

    @Test
    void substituteThenSimplify() {
        SymExpr expr = SymExpr.add(SymExpr.var("x"), SymExpr.lit(5));
        SymExpr substituted = Substitute.apply(expr, Map.of("x", SymExpr.lit(2)));
        assertEquals(SymExpr.lit(7), ARITH.simplify(substituted));
    }

    @Test
    void forceGroundedReturnsValue() {
        assertEquals(42L, Force.apply(SymExpr.lit(42)));
    }

    @Test
    void forceComplexGroundedExpression() {
        SymExpr expr = SymExpr.mul(
                SymExpr.add(SymExpr.lit(2), SymExpr.lit(3)),
                SymExpr.lit(4));
        assertEquals(20L, Force.apply(expr));
    }

    @Test
    void forceUnresolvedThrows() {
        UnresolvedSymbolException ex = assertThrows(
                UnresolvedSymbolException.class,
                () -> Force.apply(SymExpr.var("x")));
        assertTrue(ex.getMessage().contains("x"),
                "diagnostic should name the unresolved var; got: " + ex.getMessage());
    }

    @Test
    void forceUnresolvedInsideExpressionThrows() {
        UnresolvedSymbolException ex = assertThrows(
                UnresolvedSymbolException.class,
                () -> Force.apply(SymExpr.add(SymExpr.lit(1), SymExpr.var("ghost"))));
        assertTrue(ex.getMessage().contains("ghost"),
                "diagnostic should name the unresolved var; got: " + ex.getMessage());
    }

    @Test
    void partialEvalLeavesDeferredRoot() {
        SymExpr expr = SymExpr.add(
                SymExpr.var("x"),
                SymExpr.add(SymExpr.lit(2), SymExpr.lit(3)));
        assertEquals(
                SymExpr.add(SymExpr.var("x"), SymExpr.lit(5)),
                ARITH.simplify(expr));
    }
}
