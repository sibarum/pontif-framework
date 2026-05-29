package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.Context;
import sibarum.pontif.core.symbolic.FunctionCheck;
import sibarum.pontif.core.symbolic.FunctionDecl;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Sign;
import sibarum.pontif.core.symbolic.SignAnalysis;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.core.symbolic.DefaultRules;
import sibarum.pontif.core.symbolic.HypothesisRules;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignAnalysisTest {

    private static final Simplifier SIMPLIFIER = new Simplifier(allRules());

    private static List<RewriteRule> allRules() {
        List<RewriteRule> all = DefaultRules.production();
        all.addAll(HypothesisRules.all());
        return all;
    }

    // --- Lattice arithmetic ---

    @Test
    void positivePlusPositiveIsPositive() throws Exception {
        assertEquals(Sign.POSITIVE, Sign.POSITIVE.add(Sign.POSITIVE));
    }

    @Test
    void nonNegativePlusNonNegativeIsNonNegative() throws Exception {
        assertEquals(Sign.NON_NEGATIVE, Sign.NON_NEGATIVE.add(Sign.NON_NEGATIVE));
    }

    @Test
    void positivePlusNegativeIsTop() throws Exception {
        assertEquals(Sign.TOP, Sign.POSITIVE.add(Sign.NEGATIVE));
    }

    @Test
    void positiveTimesPositiveIsPositive() throws Exception {
        assertEquals(Sign.POSITIVE, Sign.POSITIVE.multiply(Sign.POSITIVE));
    }

    @Test
    void negativeTimesNegativeIsPositive() throws Exception {
        assertEquals(Sign.POSITIVE, Sign.NEGATIVE.multiply(Sign.NEGATIVE));
    }

    @Test
    void zeroTimesAnythingIsZero() throws Exception {
        assertEquals(Sign.ZERO, Sign.ZERO.multiply(Sign.POSITIVE));
        assertEquals(Sign.ZERO, Sign.ZERO.multiply(Sign.TOP));
        assertEquals(Sign.ZERO, Sign.TOP.multiply(Sign.ZERO));
    }

    @Test
    void contradictoryHypothesesGiveBottom() throws Exception {
        // x > 0 ∧ x = 0 → BOTTOM
        Sign s = Sign.POSITIVE.meet(Sign.ZERO);
        assertEquals(Sign.BOTTOM, s);
    }

    // --- computeSign over compound expressions ---

    @Test
    void sumOfPositiveVars_isPositive() throws Exception {
        SymExpr expr = SymExpr.add(SymExpr.var("x"), SymExpr.var("y"));
        List<SymExpr> hyps = List.of(
                SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(0)),
                SymExpr.cmp(SymExpr.var("y"), SymExpr.CmpOp.GT, SymExpr.lit(0)));
        assertEquals(Sign.POSITIVE, SignAnalysis.computeSign(expr, hyps));
    }

    @Test
    void productOfPositiveVars_isPositive() throws Exception {
        SymExpr expr = SymExpr.mul(SymExpr.var("x"), SymExpr.var("y"));
        List<SymExpr> hyps = List.of(
                SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(0)),
                SymExpr.cmp(SymExpr.var("y"), SymExpr.CmpOp.GT, SymExpr.lit(0)));
        assertEquals(Sign.POSITIVE, SignAnalysis.computeSign(expr, hyps));
    }

    @Test
    void productOfTwoNegatives_isPositive() throws Exception {
        SymExpr expr = SymExpr.mul(SymExpr.var("x"), SymExpr.var("y"));
        List<SymExpr> hyps = List.of(
                SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.LT, SymExpr.lit(0)),
                SymExpr.cmp(SymExpr.var("y"), SymExpr.CmpOp.LT, SymExpr.lit(0)));
        assertEquals(Sign.POSITIVE, SignAnalysis.computeSign(expr, hyps));
    }

    @Test
    void squareOfAnyVar_isNonNegative() throws Exception {
        // x * x for unknown x → NON_NEGATIVE (squares are always >= 0)
        SymExpr expr = SymExpr.mul(SymExpr.var("x"), SymExpr.var("x"));
        // No hypotheses about x
        assertEquals(Sign.NON_NEGATIVE,
                SignAnalysis.computeSign(expr, List.of()));
    }

    @Test
    void squareOfNonNegativeVar_isNonNegative() throws Exception {
        SymExpr expr = SymExpr.mul(SymExpr.var("x"), SymExpr.var("x"));
        List<SymExpr> hyps = List.of(
                SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GE, SymExpr.lit(0)));
        assertEquals(Sign.NON_NEGATIVE, SignAnalysis.computeSign(expr, hyps));
    }

    @Test
    void powEvenExponentOfUnknown_isNonNegative() throws Exception {
        // x^2 → NON_NEGATIVE regardless of x's sign
        SymExpr expr = SymExpr.pow(SymExpr.var("x"), SymExpr.lit(2));
        assertEquals(Sign.NON_NEGATIVE, SignAnalysis.computeSign(expr, List.of()));
    }

    @Test
    void powEvenExponentOfPositive_isPositive() throws Exception {
        SymExpr expr = SymExpr.pow(SymExpr.var("x"), SymExpr.lit(2));
        List<SymExpr> hyps = List.of(
                SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(0)));
        assertEquals(Sign.POSITIVE, SignAnalysis.computeSign(expr, hyps));
    }

    @Test
    void powOddExponentOfNegative_isNegative() throws Exception {
        SymExpr expr = SymExpr.pow(SymExpr.var("x"), SymExpr.lit(3));
        List<SymExpr> hyps = List.of(
                SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.LT, SymExpr.lit(0)));
        assertEquals(Sign.NEGATIVE, SignAnalysis.computeSign(expr, hyps));
    }

    // --- Sign-based discharge in the Simplifier ---

    @Test
    void simplifier_dischargesCompoundCmpFromSignHypothesis() throws Exception {
        // Hypothesis: x > 0, y > 0
        // Goal: x + y > 0  — compound subject
        SymExpr goal = SymExpr.cmp(
                SymExpr.add(SymExpr.var("x"), SymExpr.var("y")),
                SymExpr.CmpOp.GT,
                SymExpr.lit(0));
        Simplifier withFacts = SIMPLIFIER.withContext(Context.of(
                SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(0)),
                SymExpr.cmp(SymExpr.var("y"), SymExpr.CmpOp.GT, SymExpr.lit(0))));
        assertEquals(SymExpr.bool(true), withFacts.simplify(goal));
    }

    @Test
    void simplifier_squareIsNonNegativeWithoutHypotheses() throws Exception {
        // x * x >= 0  is true for ANY x — sign analysis derives NON_NEGATIVE for x*x
        SymExpr goal = SymExpr.cmp(
                SymExpr.mul(SymExpr.var("x"), SymExpr.var("x")),
                SymExpr.CmpOp.GE,
                SymExpr.lit(0));
        assertEquals(SymExpr.bool(true), SIMPLIFIER.simplify(goal));
    }

    @Test
    void simplifier_squareIsPositiveWhenBaseIsPositive() throws Exception {
        SymExpr goal = SymExpr.cmp(
                SymExpr.mul(SymExpr.var("x"), SymExpr.var("x")),
                SymExpr.CmpOp.GT,
                SymExpr.lit(0));
        Simplifier withFacts = SIMPLIFIER.withContext(Context.of(
                SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(0))));
        assertEquals(SymExpr.bool(true), withFacts.simplify(goal));
    }

    @Test
    void simplifier_doesNotProveStrictPositivityFromNonNegative() throws Exception {
        // x >= 0 ⊬ x*x > 0  (x could be 0)
        SymExpr goal = SymExpr.cmp(
                SymExpr.mul(SymExpr.var("x"), SymExpr.var("x")),
                SymExpr.CmpOp.GT,
                SymExpr.lit(0));
        Simplifier withFacts = SIMPLIFIER.withContext(Context.of(
                SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GE, SymExpr.lit(0))));
        SymExpr result = withFacts.simplify(goal);
        // Should NOT fold to true — sign analysis correctly admits NON_NEGATIVE doesn't imply > 0
        assertInstanceOf(SymExpr.Cmp.class, result);
    }

    // --- The headline: square() now verifies under rung 2.5 ---

    @Test
    void squareFunction_definitionVerifiesUnderRung25() throws Exception {
        // square(x: Int[@>=0]) : Int[@>=0] = x * x
        // Under rung 2.5, the precondition x>=0 derives sign(x)=NON_NEGATIVE,
        // so sign(x*x) = NON_NEGATIVE which satisfies @>=0.
        Sort nonNegative = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(0)));
        FunctionDecl square = FunctionDecl.definition(
                "square",
                List.of(new FunctionDecl.Param("x", nonNegative)),
                nonNegative,
                SymExpr.mul(SymExpr.var("x"), SymExpr.var("x")));

        ProofResult r = FunctionCheck.verifyDefinition(square, SIMPLIFIER);
        assertTrue(r.isPassed(),
                "rung-2.5 sign analysis should discharge square's postcondition; got " + r);
    }

    @Test
    void squareFunction_strictPositiveReturnSort_stillResidual() throws Exception {
        // square(x: Int[@>=0]) : Int[@>0] = x * x
        // x*x > 0 doesn't follow from x>=0 (x could be 0), so honest residual
        Sort nonNegative = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(0)));
        Sort positive = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)));
        FunctionDecl square = FunctionDecl.definition(
                "square",
                List.of(new FunctionDecl.Param("x", nonNegative)),
                positive,
                SymExpr.mul(SymExpr.var("x"), SymExpr.var("x")));

        ProofResult r = FunctionCheck.verifyDefinition(square, SIMPLIFIER);
        assertFalse(r.isPassed(),
                "x>=0 doesn't imply x*x>0 (x could be 0); should not falsely pass. Got: " + r);
    }

    @Test
    void squareFunction_strictPositiveBothEnds_passesUnderRung25() throws Exception {
        // square(x: Int[@>0]) : Int[@>0] = x * x
        // x > 0 → sign(x) = POSITIVE → sign(x*x) = POSITIVE → satisfies @>0
        Sort positive = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)));
        FunctionDecl square = FunctionDecl.definition(
                "square",
                List.of(new FunctionDecl.Param("x", positive)),
                positive,
                SymExpr.mul(SymExpr.var("x"), SymExpr.var("x")));

        ProofResult r = FunctionCheck.verifyDefinition(square, SIMPLIFIER);
        assertTrue(r.isPassed(),
                "x>0 implies x*x>0; rung-2.5 should discharge. Got: " + r);
    }

    @Test
    void sumOfSquaresIsNonNegative_verifiesForAnyInputs() throws Exception {
        // f(x: Int, y: Int) : Int[@>=0] = x*x + y*y
        // No precondition needed — sign analysis derives x*x >= 0 and y*y >= 0
        // unconditionally; their sum is >= 0.
        Sort anyInt = Sort.of("Int");
        Sort nonNegative = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(0)));
        FunctionDecl sumOfSquares = FunctionDecl.definition(
                "sumOfSquares",
                List.of(
                        new FunctionDecl.Param("x", anyInt),
                        new FunctionDecl.Param("y", anyInt)),
                nonNegative,
                SymExpr.add(
                        SymExpr.mul(SymExpr.var("x"), SymExpr.var("x")),
                        SymExpr.mul(SymExpr.var("y"), SymExpr.var("y"))));

        ProofResult r = FunctionCheck.verifyDefinition(sumOfSquares, SIMPLIFIER);
        assertTrue(r.isPassed(),
                "x*x + y*y >= 0 for any x, y — sign analysis should discharge. Got: " + r);
    }
}
