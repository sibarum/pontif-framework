package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.demo.symbolic.ArithmeticRules;
import sibarum.pontif.demo.symbolic.CaseRules;
import sibarum.pontif.demo.symbolic.RefinementRules;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseTest {

    private static final Simplifier SIMPLIFIER = new Simplifier(combinedRules());

    private static List<RewriteRule> combinedRules() {
        List<RewriteRule> all = new ArrayList<>();
        all.addAll(CaseRules.all());
        all.addAll(RefinementRules.all());
        all.addAll(ArithmeticRules.all());
        return all;
    }

    private static final Sort POSITIVE = Sort.refined("Int",
            SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)));
    private static final Sort NEGATIVE = Sort.refined("Int",
            SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.LT, SymExpr.lit(0)));
    private static final Sort ZERO = Sort.refined("Int",
            SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.EQ, SymExpr.lit(0)));
    private static final Sort ANY = Sort.of("Int");

    @Test
    void concreteMatch_selectsRightBranch_positive() {
        // match 5 with | positive -> 2*self | _ -> self
        SymExpr expr = SymExpr.case_(
                SymExpr.lit(5),
                List.of(
                        SymExpr.branch(POSITIVE, SymExpr.mul(SymExpr.self(), SymExpr.lit(2))),
                        SymExpr.branch(ANY, SymExpr.self())));
        assertEquals(SymExpr.lit(10), SIMPLIFIER.simplify(expr));
    }

    @Test
    void concreteMatch_fallsThroughToNegativeBranch() {
        // match -3 with | positive -> 2*self | negative -> 0-self
        SymExpr expr = SymExpr.case_(
                SymExpr.lit(-3),
                List.of(
                        SymExpr.branch(POSITIVE, SymExpr.mul(SymExpr.self(), SymExpr.lit(2))),
                        SymExpr.branch(NEGATIVE, SymExpr.mul(SymExpr.lit(-1), SymExpr.self()))));
        assertEquals(SymExpr.lit(3), SIMPLIFIER.simplify(expr));
    }

    @Test
    void concreteMatch_zeroBranchFires() {
        SymExpr expr = SymExpr.case_(
                SymExpr.lit(0),
                List.of(
                        SymExpr.branch(POSITIVE, SymExpr.lit(1)),
                        SymExpr.branch(ZERO, SymExpr.lit(42)),
                        SymExpr.branch(NEGATIVE, SymExpr.lit(-1))));
        assertEquals(SymExpr.lit(42), SIMPLIFIER.simplify(expr));
    }

    @Test
    void noMatchingBranch_leavesCaseUnreduced() {
        // -3 doesn't match positive; no fallback
        SymExpr expr = SymExpr.case_(
                SymExpr.lit(-3),
                List.of(SymExpr.branch(POSITIVE, SymExpr.lit(99))));
        SymExpr result = SIMPLIFIER.simplify(expr);
        assertInstanceOf(SymExpr.Case.class, result,
                "Case with no matching branch should remain a Case (residual program state)");
    }

    @Test
    void symbolicScrutinee_leavesCaseUnreduced() {
        // x is symbolic — can't decide which branch fires
        SymExpr expr = SymExpr.case_(
                SymExpr.var("x"),
                List.of(
                        SymExpr.branch(POSITIVE, SymExpr.lit(1)),
                        SymExpr.branch(NEGATIVE, SymExpr.lit(-1))));
        SymExpr result = SIMPLIFIER.simplify(expr);
        assertInstanceOf(SymExpr.Case.class, result);
    }

    @Test
    void firstMatchWins() {
        // 5 matches both POSITIVE and ANY; the first should fire
        SymExpr expr = SymExpr.case_(
                SymExpr.lit(5),
                List.of(
                        SymExpr.branch(POSITIVE, SymExpr.lit(100)),
                        SymExpr.branch(ANY, SymExpr.lit(200))));
        assertEquals(SymExpr.lit(100), SIMPLIFIER.simplify(expr));
    }

    @Test
    void selfInArmBodyResolvesToScrutinee() {
        // match 7 with | _ -> self * self
        SymExpr expr = SymExpr.case_(
                SymExpr.lit(7),
                List.of(SymExpr.branch(ANY, SymExpr.mul(SymExpr.self(), SymExpr.self()))));
        assertEquals(SymExpr.lit(49), SIMPLIFIER.simplify(expr));
    }

    @Test
    void residualOnEarlierBranch_blocksLaterBranches() {
        // Symbolic scrutinee makes POSITIVE residual; we must NOT commit to ANY
        SymExpr expr = SymExpr.case_(
                SymExpr.var("x"),
                List.of(
                        SymExpr.branch(POSITIVE, SymExpr.lit(1)),
                        SymExpr.branch(ANY, SymExpr.lit(2))));
        SymExpr result = SIMPLIFIER.simplify(expr);
        // Should NOT collapse to Lit(2) even though ANY would match anything
        assertInstanceOf(SymExpr.Case.class, result,
                "residual on POSITIVE must block ANY to preserve first-match semantics");
    }

    @Test
    void failedEarlyBranch_allowsLaterBranchToFire() {
        // Concrete -5 definitively FAILS POSITIVE (not residual), so we may take ANY
        SymExpr expr = SymExpr.case_(
                SymExpr.lit(-5),
                List.of(
                        SymExpr.branch(POSITIVE, SymExpr.lit(1)),
                        SymExpr.branch(ANY, SymExpr.lit(2))));
        assertEquals(SymExpr.lit(2), SIMPLIFIER.simplify(expr));
    }

    @Test
    void nestedCase_innerSelfRefersToInnerScrutinee() {
        // match 3 with
        //   | _ -> match 7 with
        //            | _ -> self * self      <-- inner self should be 7, giving 49
        SymExpr innerCase = SymExpr.case_(
                SymExpr.lit(7),
                List.of(SymExpr.branch(ANY, SymExpr.mul(SymExpr.self(), SymExpr.self()))));
        SymExpr outerCase = SymExpr.case_(
                SymExpr.lit(3),
                List.of(SymExpr.branch(ANY, innerCase)));
        assertEquals(SymExpr.lit(49), SIMPLIFIER.simplify(outerCase));
    }

    @Test
    void nestedCase_outerSelfPersistsIntoInnerScrutinee() {
        // match 7 with
        //   | _ -> match self with               <-- self here is outer scrutinee, so 7
        //            | _ -> self + 1            <-- self here is inner scrutinee, also 7
        SymExpr innerCase = SymExpr.case_(
                SymExpr.self(),
                List.of(SymExpr.branch(ANY, SymExpr.add(SymExpr.self(), SymExpr.lit(1)))));
        SymExpr outerCase = SymExpr.case_(
                SymExpr.lit(7),
                List.of(SymExpr.branch(ANY, innerCase)));
        assertEquals(SymExpr.lit(8), SIMPLIFIER.simplify(outerCase));
    }

    @Test
    void caseAsExpression_composesWithArithmetic() {
        // (match 5 with | positive -> 10 | _ -> 0) + 7  ==  17
        SymExpr inner = SymExpr.case_(
                SymExpr.lit(5),
                List.of(
                        SymExpr.branch(POSITIVE, SymExpr.lit(10)),
                        SymExpr.branch(ANY, SymExpr.lit(0))));
        SymExpr combined = SymExpr.add(inner, SymExpr.lit(7));
        assertEquals(SymExpr.lit(17), SIMPLIFIER.simplify(combined));
    }

    @Test
    void singleBranchAny_alwaysFires() {
        // The canonical "identity" pattern — Sort.of with no refinement
        SymExpr expr = SymExpr.case_(
                SymExpr.lit(42),
                List.of(SymExpr.branch(ANY, SymExpr.self())));
        assertEquals(SymExpr.lit(42), SIMPLIFIER.simplify(expr));
    }
}
