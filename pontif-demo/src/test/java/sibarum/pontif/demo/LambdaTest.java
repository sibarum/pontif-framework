package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.defaults.DefaultRules;
import sibarum.pontif.core.symbolic.LambdaRules;
import sibarum.pontif.core.symbolic.TotalExpressionRules;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LambdaTest {

    private static final Simplifier LAMBDA = new Simplifier(LambdaRules.all());

    private static final Simplifier COMBINED = new Simplifier(combinedRules());

    private static List<RewriteRule> combinedRules() {
        List<RewriteRule> all = DefaultRules.production();
        all.addAll(LambdaRules.all());
        all.addAll(TotalExpressionRules.all());
        return all;
    }

    @Test
    void identityFunction() throws Exception {
        // (λx.x)(5) = 5
        SymExpr expr = SymExpr.app(
                SymExpr.lam("x", SymExpr.var("x")),
                SymExpr.lit(5));
        assertEquals(SymExpr.lit(5), LAMBDA.simplify(expr));
    }

    @Test
    void constantFunction() throws Exception {
        // (λx.7)(anything) = 7
        SymExpr expr = SymExpr.app(
                SymExpr.lam("x", SymExpr.lit(7)),
                SymExpr.var("anything"));
        assertEquals(SymExpr.lit(7), LAMBDA.simplify(expr));
    }

    @Test
    void squaringFunction_concrete() throws Exception {
        // (λx. x · x)(3) = 9 — needs CONSTANT_FOLD_MUL from ArithmeticRules
        SymExpr expr = SymExpr.app(
                SymExpr.lam("x", SymExpr.mul(SymExpr.var("x"), SymExpr.var("x"))),
                SymExpr.lit(3));
        assertEquals(SymExpr.lit(9), COMBINED.simplify(expr));
    }

    @Test
    void squaringTheImaginaryUnit_composesAllRules() throws Exception {
        // (λx. x · x)(i) = -1 — composes lambda calc with Total Expression rules
        SymExpr i = SymExpr.pow(SymExpr.lit(-1), SymExpr.frac(1, 2));
        SymExpr expr = SymExpr.app(
                SymExpr.lam("x", SymExpr.mul(SymExpr.var("x"), SymExpr.var("x"))),
                i);
        assertEquals(SymExpr.lit(-1), COMBINED.simplify(expr));
    }

    @Test
    void compositionOfTwoFunctions() throws Exception {
        // compose = λg. λf. λx. g(f(x))
        SymExpr compose = SymExpr.lam("g",
                SymExpr.lam("f",
                        SymExpr.lam("x",
                                SymExpr.app(
                                        SymExpr.var("g"),
                                        SymExpr.app(SymExpr.var("f"), SymExpr.var("x"))))));
        // square = λy. y · y
        SymExpr square = SymExpr.lam("y", SymExpr.mul(SymExpr.var("y"), SymExpr.var("y")));
        // addOne = λz. z + 1
        SymExpr addOne = SymExpr.lam("z", SymExpr.add(SymExpr.var("z"), SymExpr.lit(1)));
        // compose(square)(addOne)(3) = square(addOne(3)) = square(4) = 16
        SymExpr expr = SymExpr.app(
                SymExpr.app(SymExpr.app(compose, square), addOne),
                SymExpr.lit(3));
        assertEquals(SymExpr.lit(16), COMBINED.simplify(expr));
    }

    @Test
    void higherOrderApplyTwice() throws Exception {
        // applyTwice = λf. λx. f(f(x))
        SymExpr applyTwice = SymExpr.lam("f",
                SymExpr.lam("x",
                        SymExpr.app(
                                SymExpr.var("f"),
                                SymExpr.app(SymExpr.var("f"), SymExpr.var("x")))));
        // applyTwice(λy.y·y)(3) = ((3·3)·(3·3)) = 9·9 = 81
        SymExpr square = SymExpr.lam("y", SymExpr.mul(SymExpr.var("y"), SymExpr.var("y")));
        SymExpr expr = SymExpr.app(SymExpr.app(applyTwice, square), SymExpr.lit(3));
        assertEquals(SymExpr.lit(81), COMBINED.simplify(expr));
    }

    @Test
    void captureAvoidance_innerLambdaIsRenamed() throws Exception {
        // (λx. λy. x)(y) — applying to free var "y" must NOT capture
        // After beta: should be λy_fresh. y  (the inner y is renamed, body's x is replaced with outer y)
        SymExpr expr = SymExpr.app(
                SymExpr.lam("x", SymExpr.lam("y", SymExpr.var("x"))),
                SymExpr.var("y"));
        SymExpr reduced = LAMBDA.simplify(expr);

        SymExpr.Lam outerLam = assertInstanceOf(SymExpr.Lam.class, reduced);
        assertNotEquals("y", outerLam.param(),
                "inner parameter should have been alpha-renamed to avoid capturing outer y");
        assertEquals(SymExpr.var("y"), outerLam.body(),
                "body should be the substituted outer y, unaffected by the inner binding");
    }

    @Test
    void shadowing_outerVarDoesNotLeakInto_innerScope() throws Exception {
        // (λx. λx. x)(5) — beta-reduces by removing the outer x
        // The inner x shadows; the substitution of outer x → 5 has no effect on the inner λx.x
        SymExpr expr = SymExpr.app(
                SymExpr.lam("x", SymExpr.lam("x", SymExpr.var("x"))),
                SymExpr.lit(5));
        SymExpr reduced = LAMBDA.simplify(expr);
        // Result should be identity function λx.x (the inner one), not λx.5
        SymExpr.Lam asLam = assertInstanceOf(SymExpr.Lam.class, reduced);
        assertEquals("x", asLam.param());
        assertEquals(SymExpr.var("x"), asLam.body());
    }

    @Test
    void untypedLambda_paramTypeIsNull() throws Exception {
        SymExpr untyped = SymExpr.lam("x", SymExpr.var("x"));
        SymExpr.Lam asLam = assertInstanceOf(SymExpr.Lam.class, untyped);
        assertNull(asLam.paramType());
        assertEquals(SymExpr.lit(42),
                LAMBDA.simplify(SymExpr.app(untyped, SymExpr.lit(42))));
    }

    @Test
    void typedLambda_paramTypeIsRecorded() throws Exception {
        Sort nat = Sort.of("Nat");
        SymExpr typed = SymExpr.lam("x", nat, SymExpr.var("x"));
        SymExpr.Lam asLam = assertInstanceOf(SymExpr.Lam.class, typed);
        assertEquals(nat, asLam.paramType());
        // Type annotation is informational only at this slice; evaluation proceeds untyped
        assertEquals(SymExpr.lit(42),
                LAMBDA.simplify(SymExpr.app(typed, SymExpr.lit(42))));
    }

    @Test
    void lambdaAsValue_passedToHigherOrder() throws Exception {
        // Pass a typed lambda as an argument and let it survive a beta-reduction unchanged
        Sort nat = Sort.of("Nat");
        SymExpr typedSquare = SymExpr.lam("y", nat,
                SymExpr.mul(SymExpr.var("y"), SymExpr.var("y")));
        // (λf. f(4))(typedSquare) = typedSquare(4) = 16
        SymExpr applyTo4 = SymExpr.lam("f",
                SymExpr.app(SymExpr.var("f"), SymExpr.lit(4)));
        SymExpr expr = SymExpr.app(applyTo4, typedSquare);
        assertEquals(SymExpr.lit(16), COMBINED.simplify(expr));
    }

    @Test
    void unappliedLambda_isFixedPointOfSimplification() throws Exception {
        // An unapplied lambda doesn't reduce — it's a value
        SymExpr value = SymExpr.lam("x", SymExpr.var("x"));
        assertEquals(value, LAMBDA.simplify(value));
    }
}
