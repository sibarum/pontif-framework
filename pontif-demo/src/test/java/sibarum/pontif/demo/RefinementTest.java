package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.DefaultRules;
import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.types.Sort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefinementTest {

    private static final Simplifier SIMPLIFIER = new Simplifier(DefaultRules.production());

    // --- Self and Bool primitives ---

    @Test
    void selfIsItsOwnInstance() throws Exception {
        assertEquals(SymExpr.self(), SymExpr.self());
    }

    @Test
    void boolLiteralForcesToBoolean() throws Exception {
        assertEquals(true, sibarum.pontif.core.symbolic.Force.apply(SymExpr.bool(true)));
        assertEquals(false, sibarum.pontif.core.symbolic.Force.apply(SymExpr.bool(false)));
    }

    @Test
    void cmpOfConcreteLiteralsFoldsToBool() throws Exception {
        assertEquals(SymExpr.bool(true),
                SIMPLIFIER.simplify(SymExpr.cmp(SymExpr.lit(5), SymExpr.CmpOp.GT, SymExpr.lit(3))));
        assertEquals(SymExpr.bool(false),
                SIMPLIFIER.simplify(SymExpr.cmp(SymExpr.lit(2), SymExpr.CmpOp.GT, SymExpr.lit(7))));
        assertEquals(SymExpr.bool(true),
                SIMPLIFIER.simplify(SymExpr.cmp(SymExpr.lit(4), SymExpr.CmpOp.EQ, SymExpr.lit(4))));
    }

    // --- Satisfies: concrete-value refinement check ---

    @Test
    void unrefined_sortAcceptsAnything() throws Exception {
        Sort plain = Sort.of("Int");
        assertTrue(Refinements.satisfies(SymExpr.lit(42), plain, SIMPLIFIER).isPassed());
        assertTrue(Refinements.satisfies(SymExpr.lit(-1), plain, SIMPLIFIER).isPassed());
    }

    @Test
    void positiveRefinement_acceptsPositive() throws Exception {
        Sort positive = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)));
        assertTrue(Refinements.satisfies(SymExpr.lit(5), positive, SIMPLIFIER).isPassed());
    }

    @Test
    void positiveRefinement_rejectsNonPositive() throws Exception {
        Sort positive = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)));
        ProofResult r1 = Refinements.satisfies(SymExpr.lit(0), positive, SIMPLIFIER);
        assertFalse(r1.isPassed());
        assertInstanceOf(ProofResult.Failed.class, r1);

        ProofResult r2 = Refinements.satisfies(SymExpr.lit(-3), positive, SIMPLIFIER);
        assertFalse(r2.isPassed());
        assertInstanceOf(ProofResult.Failed.class, r2);
    }

    @Test
    void singletonRefinement_acceptsOnlyTheValue() throws Exception {
        // Int[@=1] — singleton type
        Sort one = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.EQ, SymExpr.lit(1)));
        assertTrue(Refinements.satisfies(SymExpr.lit(1), one, SIMPLIFIER).isPassed());
        assertFalse(Refinements.satisfies(SymExpr.lit(2), one, SIMPLIFIER).isPassed());
        assertFalse(Refinements.satisfies(SymExpr.lit(0), one, SIMPLIFIER).isPassed());
    }

    @Test
    void symbolicValue_yieldsResidual() throws Exception {
        // Cannot decide whether Var("x") > 0 — residual
        Sort positive = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)));
        ProofResult result = Refinements.satisfies(SymExpr.var("x"), positive, SIMPLIFIER);
        assertInstanceOf(ProofResult.Residual.class, result);
    }

    // --- Implication: tighter refinement implies looser ---

    @Test
    void unrefinedLooser_isImpliedByAnything() throws Exception {
        Sort tighter = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(5)));
        Sort looser = Sort.of("Int");
        assertTrue(Refinements.imply(tighter, looser, SIMPLIFIER).isPassed());
    }

    @Test
    void unrefinedTighter_yieldsResidualAgainstRefinedLooser() throws Exception {
        Sort tighter = Sort.of("Int");
        Sort looser = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)));
        ProofResult result = Refinements.imply(tighter, looser, SIMPLIFIER);
        assertInstanceOf(ProofResult.Residual.class, result);
    }

    @Test
    void identicalRefinements_imply() throws Exception {
        SymExpr predicate = SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0));
        Sort a = Sort.refined("Int", predicate);
        Sort b = Sort.refined("Int", predicate);
        assertTrue(Refinements.imply(a, b, SIMPLIFIER).isPassed());
    }

    @Test
    void greaterThanFive_impliesGreaterThanZero() throws Exception {
        Sort gt5 = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(5)));
        Sort gt0 = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)));
        assertTrue(Refinements.imply(gt5, gt0, SIMPLIFIER).isPassed());
    }

    @Test
    void greaterThanZero_doesNotImplyGreaterThanFive() throws Exception {
        Sort gt0 = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)));
        Sort gt5 = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(5)));
        ProofResult result = Refinements.imply(gt0, gt5, SIMPLIFIER);
        assertFalse(result.isPassed());
        assertInstanceOf(ProofResult.Failed.class, result);
    }

    @Test
    void greaterEqualToTen_impliesGreaterThanFive() throws Exception {
        Sort ge10 = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(10)));
        Sort gt5 = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(5)));
        assertTrue(Refinements.imply(ge10, gt5, SIMPLIFIER).isPassed());
    }

    @Test
    void greaterEqualToFive_doesNotImplyStrictGreaterThanFive() throws Exception {
        // x >= 5 does NOT imply x > 5 (x could be exactly 5)
        Sort ge5 = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(5)));
        Sort gt5 = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(5)));
        ProofResult result = Refinements.imply(ge5, gt5, SIMPLIFIER);
        assertFalse(result.isPassed());
        assertInstanceOf(ProofResult.Failed.class, result);
    }

    @Test
    void singletonImpliesRange() throws Exception {
        // x = 7 implies x > 0
        Sort eq7 = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.EQ, SymExpr.lit(7)));
        Sort gt0 = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)));
        assertTrue(Refinements.imply(eq7, gt0, SIMPLIFIER).isPassed());
    }

    @Test
    void singletonDoesNotImplyDisjointSingleton() throws Exception {
        Sort eq7 = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.EQ, SymExpr.lit(7)));
        Sort eq3 = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.EQ, SymExpr.lit(3)));
        ProofResult result = Refinements.imply(eq7, eq3, SIMPLIFIER);
        assertInstanceOf(ProofResult.Failed.class, result);
    }

    @Test
    void unknownPredicateShape_yieldsResidual() throws Exception {
        // A more complex predicate (not Cmp(Self, op, Lit)) gives residual obligation
        Sort weird = Sort.refined("Int",
                SymExpr.cmp(
                        SymExpr.mul(SymExpr.self(), SymExpr.lit(2)),
                        SymExpr.CmpOp.GT,
                        SymExpr.lit(10)));
        Sort gt0 = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)));
        ProofResult result = Refinements.imply(weird, gt0, SIMPLIFIER);
        assertInstanceOf(ProofResult.Residual.class, result);
    }
}
