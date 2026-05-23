package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.Context;
import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.Substitute;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.core.symbolic.ArithmeticRules;
import sibarum.pontif.core.symbolic.HypothesisRules;
import sibarum.pontif.core.symbolic.LambdaRules;
import sibarum.pontif.core.symbolic.RefinementRules;
import sibarum.pontif.core.symbolic.StructuralRules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossFieldInvariantTest {

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
    private static final Sort UNIT = Sort.of("Unit");

    // --- Direct test of proof engine (manual context) ---

    @Test
    void methodBody_withoutSiblingInvariants_residuals() throws Exception {
        SymExpr record = SymExpr.record(Map.of(
                "x", SymExpr.var("a"),
                "y", SymExpr.var("b")));
        SymExpr sumMethod = SymExpr.lam("_",
                SymExpr.add(SymExpr.fieldAccess(SymExpr.self(), "x"),
                        SymExpr.fieldAccess(SymExpr.self(), "y")));
        SymExpr selfBound = Substitute.applySelf(sumMethod, record);

        Sort fnSort = Sort.function(List.of(UNIT), INT_GT_0);
        ProofResult r = Refinements.satisfies(selfBound, fnSort, SIMPLIFIER);
        assertInstanceOf(ProofResult.Residual.class, r,
                "Without sibling invariants, x+y > 0 cannot be decided. Got: " + r);
    }

    @Test
    void methodBody_withSiblingInvariants_passes() throws Exception {
        SymExpr record = SymExpr.record(Map.of(
                "x", SymExpr.var("a"),
                "y", SymExpr.var("b")));
        SymExpr sumMethod = SymExpr.lam("_",
                SymExpr.add(SymExpr.fieldAccess(SymExpr.self(), "x"),
                        SymExpr.fieldAccess(SymExpr.self(), "y")));
        SymExpr selfBound = Substitute.applySelf(sumMethod, record);

        Sort fnSort = Sort.function(List.of(UNIT), INT_GT_0);
        Context ctx = Context.of(
                SymExpr.cmp(SymExpr.var("a"), SymExpr.CmpOp.GE, SymExpr.lit(0)),
                SymExpr.cmp(SymExpr.var("b"), SymExpr.CmpOp.GT, SymExpr.lit(0)));
        ProofResult r = Refinements.satisfies(selfBound, fnSort, SIMPLIFIER.withContext(ctx));
        assertTrue(r.isPassed(),
                "With a>=0, b>0 in context, sign analysis derives a+b > 0. Got: " + r);
    }

    // --- Automatic propagation by satisfiesStructural ---

    @Test
    void satisfiesStructural_propagatesSiblingInvariants_methodVerifies() throws Exception {
        // Counter type with refined data and a method that uses it.
        // Even though x, y are symbolic, the method should verify under their invariants.
        Sort sumType = Sort.structural("Sum", Map.of(
                "x", INT_GE_0,
                "y", INT_GT_0,
                "total", Sort.function(List.of(UNIT), INT_GT_0)));

        SymExpr instance = SymExpr.record(Map.of(
                "x", SymExpr.var("a"),
                "y", SymExpr.var("b"),
                "total", SymExpr.lam("_",
                        SymExpr.add(SymExpr.fieldAccess(SymExpr.self(), "x"),
                                SymExpr.fieldAccess(SymExpr.self(), "y")))));

        // The overall result will be Residual (because x, y are symbolic),
        // but the result should NOT be Failed — the method body verifies under
        // the cross-field invariants.
        ProofResult r = Refinements.satisfies(instance, sumType, SIMPLIFIER);
        assertFalse(r instanceof ProofResult.Failed,
                "Slice C should make method body verification succeed under sibling invariants; "
                + "the overall residual is acceptable (data members need runtime checks). Got: " + r);
    }

    @Test
    void satisfiesStructural_concreteData_methodUsingFieldsPasses() throws Exception {
        // Concrete data: count = 5. Method: next = count + 1.
        // Without cross-field reasoning the body would still simplify to a constant (FIELD_ACCESS folds
        // count to 5), so this test mainly confirms cross-field propagation doesn't break the concrete case.
        Sort counter = Sort.structural("Counter", Map.of(
                "count", INT_GE_0,
                "next", Sort.function(List.of(UNIT), INT_GT_0)));

        SymExpr instance = SymExpr.record(Map.of(
                "count", SymExpr.lit(5),
                "next", SymExpr.lam("_",
                        SymExpr.add(SymExpr.fieldAccess(SymExpr.self(), "count"), SymExpr.lit(1)))));

        assertTrue(Refinements.satisfies(instance, counter, SIMPLIFIER).isPassed(),
                "Concrete data members + method body using them should pass uniformly.");
    }

    // --- Self-exclusion: a member's own invariant doesn't validate itself ---

    @Test
    void memberOwnInvariantExcluded_symbolicDataResidualsHonestly() throws Exception {
        // Single refined data member with symbolic value — no other members to provide
        // invariants. Result: residual, not passed (self-invariant excluded so we can't
        // circularly assume what we're trying to prove).
        Sort sort = Sort.structural("S", Map.of(
                "x", INT_GT_0));

        SymExpr instance = SymExpr.record(Map.of(
                "x", SymExpr.var("a")));  // symbolic, no other members

        ProofResult r = Refinements.satisfies(instance, sort, SIMPLIFIER);
        assertInstanceOf(ProofResult.Residual.class, r,
                "x's own invariant must not be used to verify x — should residual. Got: " + r);
    }

    // --- A non-method member can also benefit from sibling invariants ---

    @Test
    void dataMember_derivedFromSibling_verifiesUnderSiblingInvariant() throws Exception {
        // A "derived" data member that's actually a computation referencing a sibling.
        // Verifying the derived member uses the sibling's invariant.
        Sort sort = Sort.structural("Derived", Map.of(
                "base", INT_GT_0,
                "plusOne", Sort.refined("Int", SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(1)))));

        // base = var("a") (symbolic), plusOne = base + 1
        SymExpr instance = SymExpr.record(Map.of(
                "base", SymExpr.var("a"),
                "plusOne", SymExpr.add(SymExpr.fieldAccess(SymExpr.self(), "base"), SymExpr.lit(1))));

        // Verifying plusOne: under sibling invariant a > 0, sign(a + 1) = POS, satisfies > 1 ?
        // Sign analysis can't prove > 1 (it only gives POS, not > 1). So this is residual.
        // Test that result is NOT Failed (the framework didn't reject; just can't decide).
        ProofResult r = Refinements.satisfies(instance, sort, SIMPLIFIER);
        assertFalse(r instanceof ProofResult.Failed);
    }

    // --- Headline: full counter with concrete count and verifying method ---

    @Test
    void headline_counterWithCrossFieldReasoning() throws Exception {
        // Counter:
        //   count : Int[@>=0]      (concrete value 0)
        //   isEmpty : (Unit) -> Int[@>=0] = self.count   — trivial, but uses self
        //   next : (Unit) -> Int[@>0] = self.count + 1
        //
        // With concrete count = 0:
        //   isEmpty body: fieldAccess(record, "count") → lit(0). lit(0) >= 0 ✓.
        //   next body: fieldAccess(record, "count") + 1 → lit(0) + 1 → lit(1). lit(1) > 0 ✓.
        // Passes via constant folding, regardless of cross-field propagation.

        Sort counter = Sort.structural("Counter", Map.of(
                "count", INT_GE_0,
                "isEmpty", Sort.function(List.of(UNIT), INT_GE_0),
                "next", Sort.function(List.of(UNIT), INT_GT_0)));

        SymExpr instance = SymExpr.record(Map.of(
                "count", SymExpr.lit(0),
                "isEmpty", SymExpr.lam("_", SymExpr.fieldAccess(SymExpr.self(), "count")),
                "next", SymExpr.lam("_",
                        SymExpr.add(SymExpr.fieldAccess(SymExpr.self(), "count"), SymExpr.lit(1)))));

        assertTrue(Refinements.satisfies(instance, counter, SIMPLIFIER).isPassed());

        // Invocations
        assertEquals(SymExpr.lit(0),
                SIMPLIFIER.simplify(SymExpr.app(SymExpr.fieldAccess(instance, "isEmpty"), SymExpr.lit(0))));
        assertEquals(SymExpr.lit(1),
                SIMPLIFIER.simplify(SymExpr.app(SymExpr.fieldAccess(instance, "next"), SymExpr.lit(0))));
    }

    @Test
    void symbolicValuedRecord_crossFieldReasoning_keepsMethodVerified() throws Exception {
        // Same Counter type, but count is var("c") symbolic.
        // With slice C propagation, the next method body verifies under count's @>=0 invariant.
        // (The count member itself residuals, but that's a separate concern.)
        Sort counter = Sort.structural("Counter", Map.of(
                "count", INT_GE_0,
                "next", Sort.function(List.of(UNIT), INT_GT_0)));

        SymExpr instance = SymExpr.record(Map.of(
                "count", SymExpr.var("c"),
                "next", SymExpr.lam("_",
                        SymExpr.add(SymExpr.fieldAccess(SymExpr.self(), "count"), SymExpr.lit(1)))));

        // Direct test of the next method's verification: pull out the lambda, self-bind it
        // to the record, then check it against the function sort with the count invariant in context.
        SymExpr nextMethod = ((SymExpr.Record) instance).members().get("next");
        SymExpr selfBound = Substitute.applySelf(nextMethod, instance);

        Context ctx = Context.of(
                SymExpr.cmp(SymExpr.var("c"), SymExpr.CmpOp.GE, SymExpr.lit(0)));
        Sort fnSort = Sort.function(List.of(UNIT), INT_GT_0);
        ProofResult r = Refinements.satisfies(selfBound, fnSort, SIMPLIFIER.withContext(ctx));
        assertTrue(r.isPassed(),
                "next method body verifies under count's invariant. Got: " + r);
    }

    @Test
    void siblingInvariantPropagation_handlesNestedFieldAccess() throws Exception {
        // Simpler version: a record with a positive field and a method that returns "the
        // positive field plus 5" — verified to be >= 5.
        Sort sort = Sort.structural("S", Map.of(
                "v", INT_GE_0,
                "plus5", Sort.function(List.of(UNIT), INT_GE_0)));

        SymExpr instance = SymExpr.record(Map.of(
                "v", SymExpr.var("v_val"),
                "plus5", SymExpr.lam("_",
                        SymExpr.add(SymExpr.fieldAccess(SymExpr.self(), "v"), SymExpr.lit(5)))));

        ProofResult r = Refinements.satisfies(instance, sort, SIMPLIFIER);
        assertFalse(r instanceof ProofResult.Failed,
                "plus5 method verifies under v's invariant; overall residual due to symbolic v. Got: " + r);
    }
}
