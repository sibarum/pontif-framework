package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.ArithmeticRules;
import sibarum.pontif.core.symbolic.BooleanRules;
import sibarum.pontif.core.symbolic.RefinementRules;
import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.StructuralRules;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.types.Sort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase B coverage: struct refinements ({@code [Point:@.x > 0]}) compile
 * through {@link SortChecker} and reduce at runtime via
 * {@link Refinements#satisfies}.
 */
class StructRefinementTest {

    private static Simplifier defaultSimplifier() {
        List<RewriteRule> rules = new ArrayList<>();
        rules.addAll(RefinementRules.all());
        rules.addAll(ArithmeticRules.all());
        rules.addAll(BooleanRules.all());
        rules.addAll(StructuralRules.all());
        return new Simplifier(rules);
    }

    /** Helper: a `struct Point(x:Int, y:Int)` declaration. */
    private static IrStmt.TypeAlias pointAlias() {
        Map<String, IrSort> members = new LinkedHashMap<>();
        members.put("x", IrSort.named("Int"));
        members.put("y", IrSort.named("Int"));
        return IrStmt.typeAlias("Point", IrSort.structural("Point", members));
    }

    // --- Compile-time validation ---------------------------------------------

    @Test
    void structRefinementOverDeclaredStruct_compiles() throws Exception {
        // function positiveX(p:[Point:@.x > 0]):Int -> 1
        IrSort.Refined paramSort = (IrSort.Refined) IrSort.refined(
                "Point",
                IrExpr.binOp(IrExpr.Op.GT,
                        IrExpr.fieldAccess(IrExpr.self(), "x"),
                        IrExpr.lit(0)));

        IrModule module = new IrModule("m",
                List.of(
                        pointAlias(),
                        IrStmt.functionDecl(
                                "positiveX",
                                List.of(new IrParam("p", paramSort)),
                                IrSort.named("Int"),
                                IrExpr.lit(1))),
                IrExpr.lit(0));

        new IrCompiler(new Simplifier(List.of())).compile(module);
    }

    @Test
    void structRefinementCrossField_compiles() throws Exception {
        // [Point:@.x + @.y > 0] — cross-field invariant
        IrSort.Refined paramSort = (IrSort.Refined) IrSort.refined(
                "Point",
                IrExpr.binOp(IrExpr.Op.GT,
                        IrExpr.binOp(IrExpr.Op.ADD,
                                IrExpr.fieldAccess(IrExpr.self(), "x"),
                                IrExpr.fieldAccess(IrExpr.self(), "y")),
                        IrExpr.lit(0)));

        IrModule module = new IrModule("m",
                List.of(
                        pointAlias(),
                        IrStmt.functionDecl(
                                "f",
                                List.of(new IrParam("p", paramSort)),
                                IrSort.named("Int"),
                                IrExpr.lit(1))),
                IrExpr.lit(0));

        new IrCompiler(new Simplifier(List.of())).compile(module);
    }

    @Test
    void structRefinementWithUnknownField_throws() {
        // [Point:@.z > 0] — `z` isn't a field of Point.
        Origin fieldOrigin = Origin.at("t.ptf", 2, 14);
        IrSort.Refined paramSort = new IrSort.Refined(
                "Point",
                new IrExpr.BinOp(
                        IrExpr.Op.GT,
                        new IrExpr.FieldAccess(IrExpr.self(), "z", fieldOrigin),
                        IrExpr.lit(0),
                        Origin.NONE),
                Origin.NONE);

        IrModule module = new IrModule("m",
                List.of(
                        pointAlias(),
                        IrStmt.functionDecl(
                                "f",
                                List.of(new IrParam("p", paramSort)),
                                IrSort.named("Int"),
                                IrExpr.lit(1))),
                IrExpr.lit(0));

        CompileException ex = assertThrows(CompileException.class,
                () -> new IrCompiler(new Simplifier(List.of())).compile(module));
        assertTrue(ex.getMessage().contains("@.z"),
                () -> "Expected mention of unknown field; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("Point"),
                () -> "Expected mention of struct name; got: " + ex.getMessage());
    }

    @Test
    void structRefinementOverUndeclaredStruct_throws() {
        // [Banana:@.peel > 0] — Banana isn't declared.
        IrSort.Refined paramSort = (IrSort.Refined) IrSort.refined(
                "Banana",
                IrExpr.binOp(IrExpr.Op.GT,
                        IrExpr.fieldAccess(IrExpr.self(), "peel"),
                        IrExpr.lit(0)));

        IrModule module = new IrModule("m",
                List.of(IrStmt.functionDecl(
                        "f",
                        List.of(new IrParam("p", paramSort)),
                        IrSort.named("Int"),
                        IrExpr.lit(1))),
                IrExpr.lit(0));

        CompileException ex = assertThrows(CompileException.class,
                () -> new IrCompiler(new Simplifier(List.of())).compile(module));
        assertTrue(ex.getMessage().contains("Banana"));
    }

    @Test
    void primitiveRefinement_stillCompiles() throws Exception {
        // Sanity: existing `[Int:@>0]` form continues to work after the
        // refinement-base loosening.
        IrSort.Refined paramSort = (IrSort.Refined) IrSort.refined(
                "Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));

        IrModule module = new IrModule("m",
                List.of(IrStmt.functionDecl(
                        "f",
                        List.of(new IrParam("n", paramSort)),
                        IrSort.named("Int"),
                        IrExpr.lit(1))),
                IrExpr.lit(0));

        new IrCompiler(new Simplifier(List.of())).compile(module);
    }

    // --- Runtime: Refinements.satisfies with struct refinements --------------

    @Test
    void runtimeSatisfies_structRefinedSort_passesOnSatisfyingRecord() {
        // [Point:@.x > 0] checked against Point{x=3, y=4}: passes.
        Sort sort = Sort.refined("Point",
                SymExpr.cmp(
                        SymExpr.fieldAccess(SymExpr.self(), "x"),
                        SymExpr.CmpOp.GT,
                        SymExpr.lit(0)));

        SymExpr value = SymExpr.record("Point", Map.of(
                "x", SymExpr.lit(3),
                "y", SymExpr.lit(4)));

        ProofResult result = Refinements.satisfies(value, sort, defaultSimplifier());
        assertTrue(result.isPassed(),
                () -> "Expected Passed; got " + result);
    }

    @Test
    void runtimeSatisfies_structRefinedSort_failsOnViolatingRecord() {
        // [Point:@.x > 0] checked against Point{x=-5, y=4}: fails.
        Sort sort = Sort.refined("Point",
                SymExpr.cmp(
                        SymExpr.fieldAccess(SymExpr.self(), "x"),
                        SymExpr.CmpOp.GT,
                        SymExpr.lit(0)));

        SymExpr value = SymExpr.record("Point", Map.of(
                "x", SymExpr.lit(-5),
                "y", SymExpr.lit(4)));

        ProofResult result = Refinements.satisfies(value, sort, defaultSimplifier());
        assertInstanceOf(ProofResult.Failed.class, result,
                () -> "Expected Failed; got " + result);
    }

    @Test
    void runtimeSatisfies_crossFieldRefinement_passesWhenSumPositive() {
        // [Point:@.x + @.y > 0] checked against Point{x=-1, y=5}: sum=4, passes.
        Sort sort = Sort.refined("Point",
                SymExpr.cmp(
                        SymExpr.add(
                                SymExpr.fieldAccess(SymExpr.self(), "x"),
                                SymExpr.fieldAccess(SymExpr.self(), "y")),
                        SymExpr.CmpOp.GT,
                        SymExpr.lit(0)));

        SymExpr value = SymExpr.record("Point", Map.of(
                "x", SymExpr.lit(-1),
                "y", SymExpr.lit(5)));

        ProofResult result = Refinements.satisfies(value, sort, defaultSimplifier());
        assertTrue(result.isPassed(),
                () -> "Cross-field invariant should pass when sum is positive; got " + result);
    }

    @Test
    void runtimeSatisfies_crossFieldRefinement_failsWhenSumNonPositive() {
        // [Point:@.x + @.y > 0] checked against Point{x=-3, y=2}: sum=-1, fails.
        Sort sort = Sort.refined("Point",
                SymExpr.cmp(
                        SymExpr.add(
                                SymExpr.fieldAccess(SymExpr.self(), "x"),
                                SymExpr.fieldAccess(SymExpr.self(), "y")),
                        SymExpr.CmpOp.GT,
                        SymExpr.lit(0)));

        SymExpr value = SymExpr.record("Point", Map.of(
                "x", SymExpr.lit(-3),
                "y", SymExpr.lit(2)));

        ProofResult result = Refinements.satisfies(value, sort, defaultSimplifier());
        assertInstanceOf(ProofResult.Failed.class, result);
    }

    @Test
    void runtimeSatisfies_symbolicMember_residuals() {
        // [Point:@.x > 0] against Point{x=var(a), y=var(b)}: can't decide → Residual.
        Sort sort = Sort.refined("Point",
                SymExpr.cmp(
                        SymExpr.fieldAccess(SymExpr.self(), "x"),
                        SymExpr.CmpOp.GT,
                        SymExpr.lit(0)));

        SymExpr value = SymExpr.record("Point", Map.of(
                "x", SymExpr.var("a"),
                "y", SymExpr.var("b")));

        ProofResult result = Refinements.satisfies(value, sort, defaultSimplifier());
        assertFalse(result instanceof ProofResult.Failed,
                () -> "Symbolic member should residual, not fail; got " + result);
        assertFalse(result.isPassed(),
                () -> "Can't pass without knowing the value; got " + result);
    }

    // --- End-to-end: compile, then verify struct refinement at runtime -------

    @Test
    void endToEnd_structRefinementCompilesAndChecksAtRuntime() throws Exception {
        // function f(p:[Point:@.x > 0]):Int -> 1
        // main: (literal Point used as the arg) — compile + verify via the
        // CompiledModule's sort table that the refinement reduces properly.
        IrSort.Refined paramSort = (IrSort.Refined) IrSort.refined(
                "Point",
                IrExpr.binOp(IrExpr.Op.GT,
                        IrExpr.fieldAccess(IrExpr.self(), "x"),
                        IrExpr.lit(0)));

        IrModule module = new IrModule("m",
                List.of(
                        pointAlias(),
                        IrStmt.functionDecl(
                                "f",
                                List.of(new IrParam("p", paramSort)),
                                IrSort.named("Int"),
                                IrExpr.lit(1))),
                IrExpr.lit(0));

        CompiledModule compiled = new IrCompiler(defaultSimplifier()).compile(module);

        // The compiled param sort should be a refined sort with "Point" base.
        Sort compiledParamSort = compiled.sortFor(paramSort);
        assertTrue(compiledParamSort.isRefined());
        assertEquals("Point", compiledParamSort.name());

        // Verify a satisfying record passes.
        SymExpr satisfying = SymExpr.record("Point", Map.of(
                "x", SymExpr.lit(3),
                "y", SymExpr.lit(4)));
        assertTrue(Refinements.satisfies(satisfying, compiledParamSort, defaultSimplifier()).isPassed());

        // Verify a violating record fails.
        SymExpr violating = SymExpr.record("Point", Map.of(
                "x", SymExpr.lit(-1),
                "y", SymExpr.lit(4)));
        assertInstanceOf(ProofResult.Failed.class,
                Refinements.satisfies(violating, compiledParamSort, defaultSimplifier()));
    }
}
