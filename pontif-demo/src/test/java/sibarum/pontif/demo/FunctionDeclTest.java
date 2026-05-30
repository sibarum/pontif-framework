package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.defaults.DefaultRules;
import sibarum.pontif.core.symbolic.FunctionCheck;
import sibarum.pontif.core.symbolic.FunctionDecl;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.types.Sort;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionDeclTest {

    private static final Simplifier SIMPLIFIER = new Simplifier(DefaultRules.production());

    private static final Sort POSITIVE = Sort.refined("Int",
            SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)));
    private static final Sort AT_LEAST_ONE = Sort.refined("Int",
            SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(1)));
    private static final Sort EXACTLY_ONE = Sort.refined("Int",
            SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.EQ, SymExpr.lit(1)));

    // pow(b: Int[@>0], x: Int[@>=1]) : Int[@>0]
    private static final FunctionDecl POW = FunctionDecl.declaration(
            "pow",
            List.of(
                    new FunctionDecl.Param("b", POSITIVE),
                    new FunctionDecl.Param("x", AT_LEAST_ONE)),
            POSITIVE);

    // --- Construction smoke tests ---

    @Test
    void declarationConstructsWithoutBody() throws Exception {
        assertFalse(POW.hasBody());
        assertEquals(2, POW.parameters().size());
        assertEquals("pow", POW.name());
    }

    @Test
    void definitionRequiresBody() throws Exception {
        FunctionDecl def = FunctionDecl.definition(
                "id",
                List.of(new FunctionDecl.Param("x", POSITIVE)),
                POSITIVE,
                SymExpr.var("x"));
        assertTrue(def.hasBody());
    }

    // --- Call-site verification ---

    @Test
    void validCall_passes() throws Exception {
        // pow(5, 2) — both arguments satisfy
        ProofResult r = FunctionCheck.verifyCall(POW,
                List.of(SymExpr.lit(5), SymExpr.lit(2)),
                SIMPLIFIER);
        assertTrue(r.isPassed(), "pow(5, 2) should pass; got " + r);
    }

    @Test
    void negativeBaseViolatesPositivePrecondition() throws Exception {
        // pow(-3, 2) — b violates @>0
        ProofResult r = FunctionCheck.verifyCall(POW,
                List.of(SymExpr.lit(-3), SymExpr.lit(2)),
                SIMPLIFIER);
        assertInstanceOf(ProofResult.Failed.class, r);
        ProofResult.Failed f = (ProofResult.Failed) r;
        assertTrue(f.witness().contains("b"),
                "diagnostic should name the failing parameter; got: " + f.witness());
    }

    @Test
    void zeroExponentViolatesGE1Precondition() throws Exception {
        // pow(5, 0) — x violates @>=1
        ProofResult r = FunctionCheck.verifyCall(POW,
                List.of(SymExpr.lit(5), SymExpr.lit(0)),
                SIMPLIFIER);
        assertInstanceOf(ProofResult.Failed.class, r);
        ProofResult.Failed f = (ProofResult.Failed) r;
        assertTrue(f.witness().contains("x"),
                "diagnostic should name the failing parameter; got: " + f.witness());
    }

    @Test
    void arityMismatch_fails() throws Exception {
        ProofResult r = FunctionCheck.verifyCall(POW,
                List.of(SymExpr.lit(5)),  // only one arg
                SIMPLIFIER);
        assertInstanceOf(ProofResult.Failed.class, r);
        ProofResult.Failed f = (ProofResult.Failed) r;
        assertTrue(f.witness().contains("Arity"),
                "diagnostic should mention arity; got: " + f.witness());
    }

    @Test
    void symbolicArgument_yieldsResidual() throws Exception {
        // pow(x, 2) where x is symbolic — can't decide if x > 0
        ProofResult r = FunctionCheck.verifyCall(POW,
                List.of(SymExpr.var("x"), SymExpr.lit(2)),
                SIMPLIFIER);
        assertInstanceOf(ProofResult.Residual.class, r);
    }

    // --- Definition verification ---

    @Test
    void bodylessDeclaration_definitionVerifiesTrivially() throws Exception {
        ProofResult r = FunctionCheck.verifyDefinition(POW, SIMPLIFIER);
        assertTrue(r.isPassed());
    }

    @Test
    void bodyProducingExactReturnValue_definitionVerifies() throws Exception {
        // forty_two() : Int[@=42] = Lit(42)
        Sort exactly42 = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.EQ, SymExpr.lit(42)));
        FunctionDecl fortyTwo = FunctionDecl.definition(
                "forty_two",
                List.of(),
                exactly42,
                SymExpr.lit(42));
        ProofResult r = FunctionCheck.verifyDefinition(fortyTwo, SIMPLIFIER);
        assertTrue(r.isPassed(), "Body produces exactly the singleton value; should pass. Got: " + r);
    }

    @Test
    void bodyProducingWrongConcreteValue_definitionFails() throws Exception {
        // forty_three() : Int[@=42] = Lit(43)
        Sort exactly42 = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.EQ, SymExpr.lit(42)));
        FunctionDecl wrong = FunctionDecl.definition(
                "wrong",
                List.of(),
                exactly42,
                SymExpr.lit(43));
        ProofResult r = FunctionCheck.verifyDefinition(wrong, SIMPLIFIER);
        assertInstanceOf(ProofResult.Failed.class, r,
                "Body produces 43 but claims to return 42; should fail. Got: " + r);
    }

    @Test
    void bodyUsingParameters_dischargesAtCompileTime() throws Exception {
        // square(x: Int[@>=0]) : Int[@>=0] = x * x
        // HypothesisRules is part of DefaultRules.production() — the
        // simplifier connects the precondition x>=0 to the postcondition
        // x*x>=0 via SignAnalysis (positive * positive >= 0) and Passes
        // at compile time. Without HypothesisRules this would Residual.
        Sort nonNegative = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(0)));
        FunctionDecl square = FunctionDecl.definition(
                "square",
                List.of(new FunctionDecl.Param("x", nonNegative)),
                nonNegative,
                SymExpr.mul(SymExpr.var("x"), SymExpr.var("x")));
        ProofResult r = FunctionCheck.verifyDefinition(square, SIMPLIFIER);
        assertTrue(r.isPassed(),
                "With HypothesisRules in production, square(x:[Int:@>=0]):[Int:@>=0] " +
                "should pass at compile time. Got: " + r);
    }

    @Test
    void linearBoundReturnSort_dischargesAtCompileTime() throws Exception {
        // inc(x: Int[@>=1]) : Int[@>1] = x + 1
        // SignAnalysis alone can only say POSITIVE — can't clear the >1
        // threshold. BoundAnalysisRules (also in production) normalizes
        // (x+1) - 1 = x ∈ [1, ∞) and folds the postcondition to true.
        // This is the receipt-graph's `inc` headline test, now also
        // pinned at the compile-time function-check layer.
        Sort atLeastOne = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(1)));
        Sort greaterThanOne = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(1)));
        FunctionDecl inc = FunctionDecl.definition(
                "inc",
                List.of(new FunctionDecl.Param("x", atLeastOne)),
                greaterThanOne,
                SymExpr.add(SymExpr.var("x"), SymExpr.lit(1)));
        ProofResult r = FunctionCheck.verifyDefinition(inc, SIMPLIFIER);
        assertTrue(r.isPassed(),
                "With BoundAnalysisRules in production, inc(x:[Int:@>=1]):[Int:@>1] " +
                "should pass at compile time. Got: " + r);
    }

    // --- Singleton-typed parameter / return ---

    @Test
    void singletonTypedParameter_acceptsOnlyTheValue() throws Exception {
        // f(x: Int[@=1]) : Int[@=1]
        FunctionDecl f = FunctionDecl.declaration(
                "f",
                List.of(new FunctionDecl.Param("x", EXACTLY_ONE)),
                EXACTLY_ONE);
        assertTrue(FunctionCheck.verifyCall(f, List.of(SymExpr.lit(1)), SIMPLIFIER).isPassed());
        assertInstanceOf(ProofResult.Failed.class,
                FunctionCheck.verifyCall(f, List.of(SymExpr.lit(2)), SIMPLIFIER));
    }

    @Test
    void powCallChain_concretePassesAndFailsCorrectly() throws Exception {
        // Direct demonstration of the user's original example
        ProofResult ok = FunctionCheck.verifyCall(POW,
                List.of(SymExpr.lit(10), SymExpr.lit(3)),
                SIMPLIFIER);
        assertTrue(ok.isPassed());

        ProofResult fail = FunctionCheck.verifyCall(POW,
                List.of(SymExpr.lit(0), SymExpr.lit(5)),
                SIMPLIFIER);
        assertInstanceOf(ProofResult.Failed.class, fail);
    }
}
