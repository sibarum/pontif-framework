package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ast.binary.Mul;
import sibarum.pontif.ast.binary.Sub;
import sibarum.pontif.ast.bind.Let;
import sibarum.pontif.ast.bind.Var;
import sibarum.pontif.ast.literal.IntLiteral;
import sibarum.pontif.ast.match.MatchNode;
import sibarum.pontif.ast.match.MatchNode.Branch;
import sibarum.pontif.core.Pontif;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.core.symbolic.ArithmeticRules;
import sibarum.pontif.core.symbolic.RefinementRules;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6MatchTest {

    private static final Simplifier SIMPLIFIER = new Simplifier(combinedRules());

    private static List<RewriteRule> combinedRules() {
        List<RewriteRule> all = new ArrayList<>();
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
    void positiveLiteral_selectsPositiveBranch() throws Exception {
        // match 5 with | positive -> 42 | _ -> -1
        var tree = MatchNode.of(IntLiteral.of(5), SIMPLIFIER, List.of(
                Branch.of(POSITIVE, IntLiteral.of(42)),
                Branch.of(ANY, IntLiteral.of(-1))));
        assertEquals(42L, Pontif.evalLong(tree));
    }

    @Test
    void negativeLiteral_fallsThroughToNegativeBranch() throws Exception {
        // match -3 with | positive -> 1 | negative -> 99 | _ -> 0
        var tree = MatchNode.of(IntLiteral.of(-3), SIMPLIFIER, List.of(
                Branch.of(POSITIVE, IntLiteral.of(1)),
                Branch.of(NEGATIVE, IntLiteral.of(99)),
                Branch.of(ANY, IntLiteral.of(0))));
        assertEquals(99L, Pontif.evalLong(tree));
    }

    @Test
    void zeroLiteral_selectsZeroBranch_betweenPositiveAndNegative() throws Exception {
        var tree = MatchNode.of(IntLiteral.of(0), SIMPLIFIER, List.of(
                Branch.of(POSITIVE, IntLiteral.of(1)),
                Branch.of(ZERO, IntLiteral.of(42)),
                Branch.of(NEGATIVE, IntLiteral.of(-1))));
        assertEquals(42L, Pontif.evalLong(tree));
    }

    @Test
    void firstMatchSemantics_takesFirstMatchingBranch() throws Exception {
        // 5 is both > 0 and >= 0; the first listed branch wins
        Sort nonNegative = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(0)));
        var tree = MatchNode.of(IntLiteral.of(5), SIMPLIFIER, List.of(
                Branch.of(POSITIVE, IntLiteral.of(1)),
                Branch.of(nonNegative, IntLiteral.of(2))));
        assertEquals(1L, Pontif.evalLong(tree));
    }

    @Test
    void noMatchingBranch_throwsRuntimeCheckException() throws Exception {
        // -3 doesn't match positive; no fallback branch
        var tree = MatchNode.of(IntLiteral.of(-3), SIMPLIFIER, List.of(
                Branch.of(POSITIVE, IntLiteral.of(99))));
        RuntimeCheckException ex = assertThrows(
                RuntimeCheckException.class,
                () -> Pontif.evalLong(tree));
        assertTrue(ex.getMessage().contains("No match branch"),
                "diagnostic should explain no branch matched; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("-3"),
                "diagnostic should cite the offending value; got: " + ex.getMessage());
    }

    @Test
    void matchOnVarScrutinee_branchesCanReferenceSameVar() throws Exception {
        // let x = 6 in match x with
        //   | Int[self == 0] -> 0
        //   | Int[self > 0]  -> 2 * x
        var tree = Let.of("x", IntLiteral.of(6),
                MatchNode.of(Var.of("x"), SIMPLIFIER, List.of(
                        Branch.of(ZERO, IntLiteral.of(0)),
                        Branch.of(POSITIVE, Mul.of(IntLiteral.of(2), Var.of("x"))))));
        assertEquals(12L, Pontif.evalLong(tree));
    }

    @Test
    void factorialStyleIdiom_dispatchesOnZeroVsPositive() throws Exception {
        // The recursion-friendly shape: branch result computes from the bound value.
        // let x = 4 in match x with
        //   | zero     -> 1
        //   | positive -> x * (x - 1)         (stand-in for the recursive call, with no recursion yet)
        var tree = Let.of("x", IntLiteral.of(4),
                MatchNode.of(Var.of("x"), SIMPLIFIER, List.of(
                        Branch.of(ZERO, IntLiteral.of(1)),
                        Branch.of(POSITIVE, Mul.of(Var.of("x"),
                                Sub.of(Var.of("x"), IntLiteral.of(1)))))));
        assertEquals(12L, Pontif.evalLong(tree)); // 4 * 3
    }

    @Test
    void emptyBranchList_isRejectedAtConstruction() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> MatchNode.of(IntLiteral.of(1), SIMPLIFIER, List.of()));
    }
}
