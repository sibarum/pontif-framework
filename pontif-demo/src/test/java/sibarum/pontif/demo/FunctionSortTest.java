package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.demo.symbolic.ArithmeticRules;
import sibarum.pontif.demo.symbolic.HypothesisRules;
import sibarum.pontif.demo.symbolic.LambdaRules;
import sibarum.pontif.demo.symbolic.RefinementRules;
import sibarum.pontif.demo.symbolic.StructuralRules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionSortTest {

    private static final Simplifier SIMPLIFIER = new Simplifier(allRules());

    private static List<RewriteRule> allRules() {
        List<RewriteRule> all = new ArrayList<>();
        all.addAll(StructuralRules.all());
        all.addAll(LambdaRules.all());
        all.addAll(HypothesisRules.all());
        all.addAll(RefinementRules.all());
        all.addAll(ArithmeticRules.all());
        return all;
    }

    private static final Sort INT_GE_0 = Sort.refined("Int",
            SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(0)));
    private static final Sort INT_GT_0 = Sort.refined("Int",
            SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)));
    private static final Sort ANY_INT = Sort.of("Int");
    private static final Sort UNIT = Sort.of("Unit");

    // --- Function sort construction ---

    @Test
    void functionSort_constructed() {
        Sort fn = Sort.function(List.of(ANY_INT, ANY_INT), ANY_INT);
        assertTrue(fn.isFunction());
        assertFalse(fn.isStructural());
        assertFalse(fn.isRefined());
        assertEquals(2, fn.functionParams().size());
        assertEquals(ANY_INT, fn.functionReturnSort());
    }

    @Test
    void functionSort_isDistinctFromOtherKinds() {
        Sort fn = Sort.function(List.of(ANY_INT), ANY_INT);
        Sort scalar = Sort.of("Int");
        Sort struct = Sort.structural("S", Map.of("x", ANY_INT));
        assertFalse(scalar.isFunction());
        assertFalse(struct.isFunction());
        assertTrue(fn.isFunction());
    }

    // --- Function-sort satisfaction ---

    @Test
    void lamSatisfiesFunctionSort_basicArity() {
        Sort fn = Sort.function(List.of(ANY_INT), ANY_INT);
        SymExpr identity = SymExpr.lam("x", SymExpr.var("x"));
        assertTrue(Refinements.satisfies(identity, fn, SIMPLIFIER).isPassed());
    }

    @Test
    void lamWithRefinedReturn_bodyPassesUnderPrecondition() {
        // (x: Int[@>=0]) -> Int[@>=0] = x   — identity, body trivially satisfies
        Sort fn = Sort.function(List.of(INT_GE_0), INT_GE_0);
        SymExpr identity = SymExpr.lam("x", SymExpr.var("x"));
        ProofResult r = Refinements.satisfies(identity, fn, SIMPLIFIER);
        assertTrue(r.isPassed(),
                "(x: @>=0) -> @>=0 = x should pass — body == param, param assumed @>=0. Got: " + r);
    }

    @Test
    void lamWithSquareBody_satisfiesNonNegReturn() {
        // (x: Int) -> Int[@>=0] = x * x
        Sort fn = Sort.function(List.of(ANY_INT), INT_GE_0);
        SymExpr square = SymExpr.lam("x", SymExpr.mul(SymExpr.var("x"), SymExpr.var("x")));
        ProofResult r = Refinements.satisfies(square, fn, SIMPLIFIER);
        assertTrue(r.isPassed(),
                "x*x is non-negative for any x; rung 2.5 should discharge. Got: " + r);
    }

    @Test
    void lamWithReturnSortViolation_fails() {
        // (x: Int[@>=0]) -> Int[@>0] = x   — x could be 0; should NOT pass
        Sort fn = Sort.function(List.of(INT_GE_0), INT_GT_0);
        SymExpr identity = SymExpr.lam("x", SymExpr.var("x"));
        ProofResult r = Refinements.satisfies(identity, fn, SIMPLIFIER);
        assertFalse(r.isPassed(),
                "x:>=0 doesn't imply x:>0 (x could be 0); should not pass. Got: " + r);
    }

    @Test
    void nonLamValue_failsFunctionSort() {
        Sort fn = Sort.function(List.of(ANY_INT), ANY_INT);
        ProofResult r = Refinements.satisfies(SymExpr.lit(5), fn, SIMPLIFIER);
        assertInstanceOf(ProofResult.Failed.class, r);
    }

    @Test
    void symbolicValue_yieldsResidualOnFunctionSort() {
        Sort fn = Sort.function(List.of(ANY_INT), ANY_INT);
        ProofResult r = Refinements.satisfies(SymExpr.var("f"), fn, SIMPLIFIER);
        assertInstanceOf(ProofResult.Residual.class, r);
    }

    // --- Function-sort implication (variance) ---

    @Test
    void functionImplies_covariantReturn() {
        // (Int) -> Int[@>0] implies (Int) -> Int[@>=0]   (tighter return → looser return)
        Sort tighterReturn = Sort.function(List.of(ANY_INT), INT_GT_0);
        Sort looserReturn = Sort.function(List.of(ANY_INT), INT_GE_0);
        assertTrue(Refinements.imply(tighterReturn, looserReturn, SIMPLIFIER).isPassed());
    }

    @Test
    void functionImplies_contravariantParam() {
        // (Int) -> Int implies (Int[@>=0]) -> Int   (looser param accepts more, so subtype)
        Sort looserParam = Sort.function(List.of(ANY_INT), ANY_INT);
        Sort tighterParam = Sort.function(List.of(INT_GE_0), ANY_INT);
        // A function accepting any Int can be used wherever a function accepting only @>=0 is expected
        assertTrue(Refinements.imply(looserParam, tighterParam, SIMPLIFIER).isPassed());
    }

    @Test
    void functionImplies_arityMismatchFails() {
        Sort unary = Sort.function(List.of(ANY_INT), ANY_INT);
        Sort binary = Sort.function(List.of(ANY_INT, ANY_INT), ANY_INT);
        assertFalse(Refinements.imply(unary, binary, SIMPLIFIER).isPassed());
    }

    // --- Records with methods: the headline ---

    @Test
    void counterRecord_withDataAndMethod_satisfiesStructuralSort() {
        // Counter type: { count : Int[@>=0], next : (Unit) -> Int[@>0] }
        Sort counter = Sort.structural("Counter", Map.of(
                "count", INT_GE_0,
                "next", Sort.function(List.of(UNIT), INT_GT_0)));

        // Instance: count = 5, next = lambda that returns self.count + 1
        SymExpr instance = SymExpr.record(Map.of(
                "count", SymExpr.lit(5),
                "next", SymExpr.lam("_",
                        SymExpr.add(SymExpr.fieldAccess(SymExpr.self(), "count"), SymExpr.lit(1)))));

        ProofResult r = Refinements.satisfies(instance, counter, SIMPLIFIER);
        assertTrue(r.isPassed(),
                "Counter with count=5 and next()=count+1 should satisfy {count:Int[@>=0], next:()->Int[@>0]}. Got: " + r);
    }

    @Test
    void counterRecord_methodReturnsWrongSort_fails() {
        // Same Counter type but next() returns 0, which violates @>0
        Sort counter = Sort.structural("Counter", Map.of(
                "count", INT_GE_0,
                "next", Sort.function(List.of(UNIT), INT_GT_0)));

        SymExpr instance = SymExpr.record(Map.of(
                "count", SymExpr.lit(5),
                "next", SymExpr.lam("_", SymExpr.lit(0))));  // returns 0, violates @>0

        ProofResult r = Refinements.satisfies(instance, counter, SIMPLIFIER);
        assertFalse(r.isPassed(),
                "next() returning 0 violates @>0; should fail. Got: " + r);
    }

    @Test
    void methodInvocation_returnsComputedValue() {
        // Construct the counter and INVOKE next()
        SymExpr instance = SymExpr.record(Map.of(
                "count", SymExpr.lit(5),
                "next", SymExpr.lam("_",
                        SymExpr.add(SymExpr.fieldAccess(SymExpr.self(), "count"), SymExpr.lit(1)))));

        // counter.next(unit) — pass anything; the param is unused
        SymExpr call = SymExpr.app(
                SymExpr.fieldAccess(instance, "next"),
                SymExpr.lit(0));

        SymExpr result = SIMPLIFIER.simplify(call);
        assertEquals(SymExpr.lit(6), result,
                "instance.next() should compute count + 1 = 6. Got: " + result);
    }

    @Test
    void selfInMethodBody_resolvesToReceiverRecord() {
        // method body: self.x + self.y
        SymExpr instance = SymExpr.record(Map.of(
                "x", SymExpr.lit(3),
                "y", SymExpr.lit(7),
                "sum", SymExpr.lam("_",
                        SymExpr.add(
                                SymExpr.fieldAccess(SymExpr.self(), "x"),
                                SymExpr.fieldAccess(SymExpr.self(), "y")))));

        SymExpr call = SymExpr.app(
                SymExpr.fieldAccess(instance, "sum"),
                SymExpr.lit(0));

        assertEquals(SymExpr.lit(10), SIMPLIFIER.simplify(call));
    }

    @Test
    void methodTakingArg_combinesArgAndReceiver() {
        // method body: self.factor * arg
        SymExpr instance = SymExpr.record(Map.of(
                "factor", SymExpr.lit(3),
                "scale", SymExpr.lam("input",
                        SymExpr.mul(
                                SymExpr.fieldAccess(SymExpr.self(), "factor"),
                                SymExpr.var("input")))));

        SymExpr call = SymExpr.app(SymExpr.fieldAccess(instance, "scale"), SymExpr.lit(7));
        assertEquals(SymExpr.lit(21), SIMPLIFIER.simplify(call));
    }

    // --- Width subtyping with methods ---

    @Test
    void recordWithExtraMembers_satisfiesStructuralWithSubsetMethods() {
        // Width subtyping: extra members (including extra methods) are fine
        Sort basicInterface = Sort.structural("HasNext", Map.of(
                "next", Sort.function(List.of(UNIT), ANY_INT)));

        SymExpr fancyInstance = SymExpr.record(Map.of(
                "count", SymExpr.lit(5),
                "label", SymExpr.var("counter1"),
                "next", SymExpr.lam("_", SymExpr.add(SymExpr.fieldAccess(SymExpr.self(), "count"), SymExpr.lit(1))),
                "reset", SymExpr.lam("_", SymExpr.lit(0))));

        assertTrue(Refinements.satisfies(fancyInstance, basicInterface, SIMPLIFIER).isPassed());
    }
}
