package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.ArithmeticRules;
import sibarum.pontif.core.symbolic.DispatchResult;
import sibarum.pontif.core.symbolic.DispatchTable;
import sibarum.pontif.core.symbolic.FunctionDecl;
import sibarum.pontif.core.symbolic.HypothesisRules;
import sibarum.pontif.core.symbolic.RefinementRules;
import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.types.Sort;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Tests for runtime support of {@link Sort.Union} and
 * {@link Sort.Intersection}:
 * <ul>
 *   <li>{@link Refinements#satisfies} accepts a value against a Union iff
 *       any branch accepts it.
 *   <li>{@link Refinements#satisfies} accepts a value against an
 *       Intersection iff every branch accepts it.
 *   <li>{@link DispatchTable} dispatches correctly on union/intersection
 *       parameter sorts.
 * </ul>
 */
class UnionIntersectionDispatchTest {

    private static final Simplifier SIMPLIFIER = new Simplifier(allRules());

    private static List<RewriteRule> allRules() {
        List<RewriteRule> all = new ArrayList<>();
        all.addAll(HypothesisRules.all());
        all.addAll(RefinementRules.all());
        all.addAll(ArithmeticRules.all());
        return all;
    }

    private static final Sort POSITIVE = Sort.refined("Int",
            SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)));
    private static final Sort NEGATIVE = Sort.refined("Int",
            SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.LT, SymExpr.lit(0)));
    private static final Sort LESS_THAN_TEN = Sort.refined("Int",
            SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.LT, SymExpr.lit(10)));

    // --- Refinements.satisfies: union semantics -----------------------------

    @Test
    void union_acceptsValueSatisfyingFirstBranch() {
        Sort union = Sort.union(List.of(POSITIVE, NEGATIVE));
        ProofResult r = Refinements.satisfies(SymExpr.lit(5), union, SIMPLIFIER);
        assertInstanceOf(ProofResult.Passed.class, r);
    }

    @Test
    void union_acceptsValueSatisfyingSecondBranch() {
        Sort union = Sort.union(List.of(POSITIVE, NEGATIVE));
        ProofResult r = Refinements.satisfies(SymExpr.lit(-3), union, SIMPLIFIER);
        assertInstanceOf(ProofResult.Passed.class, r);
    }

    @Test
    void union_rejectsValueSatisfyingNoBranch() {
        // 0 isn't positive or negative.
        Sort union = Sort.union(List.of(POSITIVE, NEGATIVE));
        ProofResult r = Refinements.satisfies(SymExpr.lit(0), union, SIMPLIFIER);
        assertInstanceOf(ProofResult.Failed.class, r);
    }

    // --- Refinements.satisfies: intersection semantics ----------------------

    @Test
    void intersection_acceptsValueSatisfyingAllBranches() {
        // [Int:@>0 & @<10] — value 5 satisfies both
        Sort both = Sort.intersection(List.of(POSITIVE, LESS_THAN_TEN));
        ProofResult r = Refinements.satisfies(SymExpr.lit(5), both, SIMPLIFIER);
        assertInstanceOf(ProofResult.Passed.class, r);
    }

    @Test
    void intersection_rejectsValueFailingABranch() {
        // 100 satisfies @>0 but fails @<10
        Sort both = Sort.intersection(List.of(POSITIVE, LESS_THAN_TEN));
        ProofResult r = Refinements.satisfies(SymExpr.lit(100), both, SIMPLIFIER);
        assertInstanceOf(ProofResult.Failed.class, r);
    }

    // --- DispatchTable with union-typed param -------------------------------

    @Test
    void dispatch_unionParam_acceptsValueSatisfyingAnyBranch() {
        Sort union = Sort.union(List.of(POSITIVE, NEGATIVE));
        FunctionDecl fn = FunctionDecl.declaration(
                "nonZero",
                List.of(new FunctionDecl.Param("x", union)),
                Sort.of("Int"));
        DispatchTable table = new DispatchTable();
        table.register(fn);

        DispatchResult r1 = table.resolve("nonZero", List.of(SymExpr.lit(5)), SIMPLIFIER);
        DispatchResult r2 = table.resolve("nonZero", List.of(SymExpr.lit(-3)), SIMPLIFIER);
        assertInstanceOf(DispatchResult.Resolved.class, r1);
        assertInstanceOf(DispatchResult.Resolved.class, r2);
    }

    @Test
    void dispatch_unionParam_rejectsValueSatisfyingNoBranch() {
        Sort union = Sort.union(List.of(POSITIVE, NEGATIVE));
        FunctionDecl fn = FunctionDecl.declaration(
                "nonZero",
                List.of(new FunctionDecl.Param("x", union)),
                Sort.of("Int"));
        DispatchTable table = new DispatchTable();
        table.register(fn);

        DispatchResult r = table.resolve("nonZero", List.of(SymExpr.lit(0)), SIMPLIFIER);
        assertInstanceOf(DispatchResult.NoMatch.class, r);
    }

    @Test
    void dispatch_intersectionParam_acceptsBoundedValue() {
        Sort bounded = Sort.intersection(List.of(POSITIVE, LESS_THAN_TEN));
        FunctionDecl fn = FunctionDecl.declaration(
                "smallPositive",
                List.of(new FunctionDecl.Param("x", bounded)),
                Sort.of("Int"));
        DispatchTable table = new DispatchTable();
        table.register(fn);

        DispatchResult inRange = table.resolve(
                "smallPositive", List.of(SymExpr.lit(5)), SIMPLIFIER);
        DispatchResult outOfRange = table.resolve(
                "smallPositive", List.of(SymExpr.lit(100)), SIMPLIFIER);
        assertInstanceOf(DispatchResult.Resolved.class, inRange);
        assertInstanceOf(DispatchResult.NoMatch.class, outOfRange);
    }
}
