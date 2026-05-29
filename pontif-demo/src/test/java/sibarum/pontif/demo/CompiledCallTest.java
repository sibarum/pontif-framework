package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.CompiledCall;
import sibarum.pontif.core.symbolic.Context;
import sibarum.pontif.core.symbolic.FunctionCheck;
import sibarum.pontif.core.symbolic.FunctionDecl;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.core.symbolic.DefaultRules;
import sibarum.pontif.core.symbolic.HypothesisRules;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledCallTest {

    private static final Simplifier SIMPLIFIER = new Simplifier(allRules());

    private static List<RewriteRule> allRules() {
        List<RewriteRule> all = DefaultRules.production();
        all.addAll(HypothesisRules.all());
        return all;
    }

    private static final Sort POSITIVE = Sort.refined("Int",
            SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)));
    private static final Sort AT_LEAST_ONE = Sort.refined("Int",
            SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(1)));

    private static final FunctionDecl POW = FunctionDecl.declaration(
            "pow",
            List.of(
                    new FunctionDecl.Param("b", POSITIVE),
                    new FunctionDecl.Param("x", AT_LEAST_ONE)),
            POSITIVE);

    // --- Static classification ---

    @Test
    void allConcrete_allPassing_isStaticallyComplete() throws Exception {
        CompiledCall call = FunctionCheck.compileCall(POW,
                List.of(SymExpr.lit(5), SymExpr.lit(2)),
                SIMPLIFIER);
        assertTrue(call.isStaticallyComplete());
        assertTrue(call.canExecute());
        assertEquals(0, call.deferredChecks().size());
        assertEquals(0, call.staticFailures().size());
    }

    @Test
    void concreteFailure_isClassifiedStaticallyFailed() throws Exception {
        CompiledCall call = FunctionCheck.compileCall(POW,
                List.of(SymExpr.lit(-3), SymExpr.lit(2)),
                SIMPLIFIER);
        assertFalse(call.canExecute());
        assertEquals(1, call.staticFailures().size());
        assertEquals(0, call.deferredChecks().size());
        assertEquals(0, call.staticFailures().get(0).parameterIndex());
    }

    @Test
    void symbolicArgWithoutHypothesis_yieldsDeferredCheck() throws Exception {
        CompiledCall call = FunctionCheck.compileCall(POW,
                List.of(SymExpr.var("x"), SymExpr.lit(2)),
                SIMPLIFIER);
        assertTrue(call.canExecute());
        assertFalse(call.isStaticallyComplete());
        assertEquals(1, call.deferredChecks().size());
        assertEquals(0, call.deferredChecks().get(0).parameterIndex());
        assertEquals("b", call.deferredChecks().get(0).parameterName());
    }

    // --- The headline: proofs ELIMINATE runtime checks ---

    @Test
    void symbolicArgWithHypothesis_isStaticallyPassed() throws Exception {
        // The hypothesis `x > 0` should let rung-2 simplification discharge `b > 0`
        // statically, leaving no runtime check.
        Simplifier withHypothesis = SIMPLIFIER.withContext(
                Context.of(SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(0))));
        CompiledCall call = FunctionCheck.compileCall(POW,
                List.of(SymExpr.var("x"), SymExpr.lit(2)),
                withHypothesis);
        assertTrue(call.isStaticallyComplete(),
                "with x > 0 in scope, all precondition checks should be discharged statically. Got: " + call);
        assertEquals(0, call.deferredChecks().size());
    }

    @Test
    void symbolicArgWithStrongerHypothesis_alsoStaticallyPassed() throws Exception {
        Simplifier withHypothesis = SIMPLIFIER.withContext(
                Context.of(SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GE, SymExpr.lit(10))));
        CompiledCall call = FunctionCheck.compileCall(POW,
                List.of(SymExpr.var("x"), SymExpr.lit(2)),
                withHypothesis);
        assertTrue(call.isStaticallyComplete());
    }

    @Test
    void symbolicArgWithTooWeakHypothesis_stillDeferred() throws Exception {
        // x > -10 doesn't imply x > 0; the runtime check must remain
        Simplifier withWeakHypothesis = SIMPLIFIER.withContext(
                Context.of(SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(-10))));
        CompiledCall call = FunctionCheck.compileCall(POW,
                List.of(SymExpr.var("x"), SymExpr.lit(2)),
                withWeakHypothesis);
        assertFalse(call.isStaticallyComplete());
        assertEquals(1, call.deferredChecks().size());
    }

    // --- Runtime execution of deferred checks ---

    @Test
    void runtimeCheck_passesWhenActualValueSatisfies() throws Exception {
        CompiledCall call = FunctionCheck.compileCall(POW,
                List.of(SymExpr.var("x"), SymExpr.lit(2)),
                SIMPLIFIER);
        // At runtime, x = 7 (satisfies > 0)
        call.executeChecks(Map.of("x", SymExpr.lit(7)), SIMPLIFIER);
        // No throw: check passed
    }

    @Test
    void runtimeCheck_throwsWhenActualValueViolates() throws Exception {
        CompiledCall call = FunctionCheck.compileCall(POW,
                List.of(SymExpr.var("x"), SymExpr.lit(2)),
                SIMPLIFIER);
        // At runtime, x = -3 (violates > 0)
        RuntimeCheckException ex = assertThrows(
                RuntimeCheckException.class,
                () -> call.executeChecks(Map.of("x", SymExpr.lit(-3)), SIMPLIFIER));
        assertTrue(ex.getMessage().contains("pow"));
        assertTrue(ex.getMessage().contains("b"),
                "diagnostic should name the failing parameter; got: " + ex.getMessage());
    }

    @Test
    void staticallyFailed_executeChecks_throwsImmediately() throws Exception {
        CompiledCall call = FunctionCheck.compileCall(POW,
                List.of(SymExpr.lit(-3), SymExpr.lit(2)),
                SIMPLIFIER);
        RuntimeCheckException ex = assertThrows(
                RuntimeCheckException.class,
                () -> call.executeChecks(Map.of(), SIMPLIFIER));
        assertTrue(ex.getMessage().contains("statically"),
                "should report static violation; got: " + ex.getMessage());
    }

    @Test
    void staticallyPassedCall_executeChecks_doesNothing() throws Exception {
        CompiledCall call = FunctionCheck.compileCall(POW,
                List.of(SymExpr.lit(5), SymExpr.lit(2)),
                SIMPLIFIER);
        // No deferred checks; executeChecks just returns without doing anything.
        call.executeChecks(Map.of(), SIMPLIFIER);
    }

    // --- Round-trip: the full pipeline showing how proofs reduce runtime overhead ---

    @Test
    void proofPipeline_strongHypothesisEliminatesCheck_weakOneKeepsIt() throws Exception {
        // Same call, same arguments — different hypothesis context yields different
        // amount of deferred work.
        SymExpr arg = SymExpr.var("x");

        Simplifier strongCtx = SIMPLIFIER.withContext(
                Context.of(SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(100))));
        CompiledCall strong = FunctionCheck.compileCall(POW, List.of(arg, SymExpr.lit(2)), strongCtx);
        assertEquals(0, strong.deferredChecks().size(),
                "strong hypothesis should discharge all precondition checks statically");

        Simplifier noCtx = SIMPLIFIER;
        CompiledCall weak = FunctionCheck.compileCall(POW, List.of(arg, SymExpr.lit(2)), noCtx);
        assertEquals(1, weak.deferredChecks().size(),
                "without hypothesis, the b > 0 precondition must be deferred to runtime");

        // The weak version still works at runtime, given a value satisfying the check
        weak.executeChecks(Map.of("x", SymExpr.lit(50)), SIMPLIFIER);
    }
}
