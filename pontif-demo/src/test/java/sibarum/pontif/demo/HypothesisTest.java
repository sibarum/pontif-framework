package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.Context;
import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.core.symbolic.ArithmeticRules;
import sibarum.pontif.core.symbolic.HypothesisRules;
import sibarum.pontif.core.symbolic.RefinementRules;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HypothesisTest {

    private static final Simplifier SIMPLIFIER = new Simplifier(allRules());

    private static List<RewriteRule> allRules() {
        List<RewriteRule> all = new ArrayList<>();
        all.addAll(HypothesisRules.all());
        all.addAll(RefinementRules.all());
        all.addAll(ArithmeticRules.all());
        return all;
    }

    // --- Direct simplifier-level demos ---

    @Test
    void withoutHypothesis_cmpOverVarStaysSymbolic() throws Exception {
        SymExpr goal = SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(0));
        assertEquals(goal, SIMPLIFIER.simplify(goal));
    }

    @Test
    void exactlyMatchingHypothesis_dischargesGoal() throws Exception {
        SymExpr fact = SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(0));
        SymExpr goal = SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(0));
        Simplifier withFacts = SIMPLIFIER.withContext(Context.of(fact));
        assertEquals(SymExpr.bool(true), withFacts.simplify(goal));
    }

    @Test
    void strongerHypothesis_dischargesGoal() throws Exception {
        // Fact: x > 5; Goal: x > 0
        SymExpr fact = SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(5));
        SymExpr goal = SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(0));
        Simplifier withFacts = SIMPLIFIER.withContext(Context.of(fact));
        assertEquals(SymExpr.bool(true), withFacts.simplify(goal));
    }

    @Test
    void weakerHypothesis_doesNotDischargeStrongerGoal() throws Exception {
        // Fact: x > -5; Goal: x > 0 — fact is too weak
        SymExpr fact = SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(-5));
        SymExpr goal = SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(0));
        Simplifier withFacts = SIMPLIFIER.withContext(Context.of(fact));
        SymExpr result = withFacts.simplify(goal);
        // Goal should remain as a Cmp (NOT folded to Bool)
        assertInstanceOf(SymExpr.Cmp.class, result);
    }

    @Test
    void equalityHypothesis_dischargesRangeGoal() throws Exception {
        // Fact: x = 7; Goal: x > 0
        SymExpr fact = SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.EQ, SymExpr.lit(7));
        SymExpr goal = SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(0));
        Simplifier withFacts = SIMPLIFIER.withContext(Context.of(fact));
        assertEquals(SymExpr.bool(true), withFacts.simplify(goal));
    }

    @Test
    void multipleHypotheses_anyMatchingOneFires() throws Exception {
        SymExpr fact1 = SymExpr.cmp(SymExpr.var("y"), SymExpr.CmpOp.GT, SymExpr.lit(100));
        SymExpr fact2 = SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GE, SymExpr.lit(1));
        SymExpr goal  = SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(0));
        Simplifier withFacts = SIMPLIFIER.withContext(Context.of(fact1, fact2));
        assertEquals(SymExpr.bool(true), withFacts.simplify(goal));
    }

    @Test
    void hypothesisAboutDifferentVar_doesNotDischarge() throws Exception {
        SymExpr fact = SymExpr.cmp(SymExpr.var("y"), SymExpr.CmpOp.GT, SymExpr.lit(5));
        SymExpr goal = SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(0));
        Simplifier withFacts = SIMPLIFIER.withContext(Context.of(fact));
        SymExpr result = withFacts.simplify(goal);
        assertInstanceOf(SymExpr.Cmp.class, result);
    }

    @Test
    void implies_isSymmetricToSelfAndVarSubjects() throws Exception {
        // Fact about Self
        SymExpr factSelf = SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(5));
        SymExpr goalSelf = SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0));
        assertTrue(Refinements.implies(factSelf, goalSelf));

        // Same shape but about a named Var
        SymExpr factVar = SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(5));
        SymExpr goalVar = SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(0));
        assertTrue(Refinements.implies(factVar, goalVar));
    }

    // --- The headline demo: hypothesis dissolves what would otherwise be Residual ---

    @Test
    void satisfies_residualByDefault_passedWithHypothesis() throws Exception {
        Sort positive = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)));

        // Without hypothesis: symbolic x → Residual
        ProofResult withoutHypothesis =
                Refinements.satisfies(SymExpr.var("x"), positive, SIMPLIFIER);
        assertInstanceOf(ProofResult.Residual.class, withoutHypothesis);

        // With hypothesis x > 0: Passed
        Simplifier withFacts = SIMPLIFIER.withContext(
                Context.of(SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(0))));
        ProofResult withHypothesis =
                Refinements.satisfies(SymExpr.var("x"), positive, withFacts);
        assertTrue(withHypothesis.isPassed(),
                "satisfying x>0 hypothesis should discharge the @>0 refinement; got " + withHypothesis);
    }

    @Test
    void satisfies_withWeakHypothesisFromCallSite_promotesResidualToFailedOrStillResidual() throws Exception {
        // x > -10 is too weak to discharge x > 0 — should NOT pass
        Sort positive = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)));
        Simplifier withWeakHypothesis = SIMPLIFIER.withContext(
                Context.of(SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(-10))));
        ProofResult r = Refinements.satisfies(SymExpr.var("x"), positive, withWeakHypothesis);
        assertFalse(r.isPassed(),
                "x > -10 is too weak; should remain Residual or Failed. Got " + r);
    }
}
