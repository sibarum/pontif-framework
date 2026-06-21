package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase D.2: compile-time call-site dispatch resolution.
 *
 * <p>Each test feeds a list of overloads and a list of static argument
 * narrowings and asserts the resolution.
 */
class StaticDispatchTest {

    private static final IrSort INT = IrSort.named("Int");
    private static final IrSort POSITIVE = IrSort.refined("Int",
            IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
    private static final IrSort NEGATIVE = IrSort.refined("Int",
            IrExpr.binOp(IrExpr.Op.LT, IrExpr.self(), IrExpr.lit(0)));
    private static final IrSort EQ_5 = IrSort.refined("Int",
            IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), IrExpr.lit(5)));

    private static IrStmt.FunctionDecl decl(String name, IrSort param, IrSort ret) {
        return IrStmt.functionDecl(name, List.of(new IrParam("x", param)), ret, IrExpr.lit(0));
    }

    private static IrStmt.FunctionDecl decl2(String name, IrSort p1, IrSort p2, IrSort ret) {
        return IrStmt.functionDecl(name,
                List.of(new IrParam("x", p1), new IrParam("y", p2)),
                ret, IrExpr.lit(0));
    }

    // --- Single overload -----------------------------------------------------

    @Test
    void singleOverload_argMatches_resolves() {
        IrStmt.FunctionDecl f = decl("f", INT, INT);
        StaticDispatch.Result r = StaticDispatch.resolve(List.of(f), List.of(EQ_5));
        assertInstanceOf(StaticDispatch.Result.Resolved.class, r);
        assertEquals(f, ((StaticDispatch.Result.Resolved) r).decl());
    }

    @Test
    void singleOverload_argFailsRefinement_unresolved() {
        // f(x:[Int:@>0]) called with arg narrowing [Int:@<0]
        IrStmt.FunctionDecl f = decl("f", POSITIVE, INT);
        StaticDispatch.Result r = StaticDispatch.resolve(List.of(f), List.of(NEGATIVE));
        assertInstanceOf(StaticDispatch.Result.Unresolved.class, r);
    }

    @Test
    void singleOverload_nullArg_unresolved() {
        // Null narrowing → can't claim definite match.
        IrStmt.FunctionDecl f = decl("f", POSITIVE, INT);
        StaticDispatch.Result r = StaticDispatch.resolve(
                List.of(f), java.util.Arrays.asList((IrSort) null));
        assertInstanceOf(StaticDispatch.Result.Unresolved.class, r);
    }

    // --- Multiple overloads, single match -----------------------------------

    @Test
    void twoDisjointOverloads_argMatchesOne_resolves() {
        IrStmt.FunctionDecl onPositive = decl("f", POSITIVE, IrSort.named("Int"));
        IrStmt.FunctionDecl onNegative = decl("f", NEGATIVE, IrSort.named("Int"));
        // Arg is exactly 5 → satisfies POSITIVE only.
        StaticDispatch.Result r = StaticDispatch.resolve(
                List.of(onPositive, onNegative), List.of(EQ_5));
        assertInstanceOf(StaticDispatch.Result.Resolved.class, r);
        assertEquals(onPositive, ((StaticDispatch.Result.Resolved) r).decl());
    }

    // --- Most-specific resolution (catch-all + specialization) --------------

    @Test
    void catchAllPlusSpecialization_argMatchesBoth_picksSpecialization() {
        IrStmt.FunctionDecl catchAll = decl("f", INT, INT);
        IrStmt.FunctionDecl specialization = decl("f", POSITIVE, INT);
        // Arg is exactly 5 → satisfies both, specialization wins.
        StaticDispatch.Result r = StaticDispatch.resolve(
                List.of(catchAll, specialization), List.of(EQ_5));
        assertInstanceOf(StaticDispatch.Result.Resolved.class, r);
        assertEquals(specialization, ((StaticDispatch.Result.Resolved) r).decl());
    }

    @Test
    void catchAllPlusSpecialization_argFailsSpecialization_picksCatchAll() {
        IrStmt.FunctionDecl catchAll = decl("f", INT, INT);
        IrStmt.FunctionDecl specialization = decl("f", POSITIVE, INT);
        IrSort eqMinus3 = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), IrExpr.lit(-3)));
        // -3 fails @>0 → only catchAll matches.
        StaticDispatch.Result r = StaticDispatch.resolve(
                List.of(catchAll, specialization), List.of(eqMinus3));
        assertInstanceOf(StaticDispatch.Result.Resolved.class, r);
        assertEquals(catchAll, ((StaticDispatch.Result.Resolved) r).decl());
    }

    // --- Multi-arg dispatch -------------------------------------------------

    @Test
    void multiArg_bothNarrowingsImply_resolves() {
        IrStmt.FunctionDecl f = decl2("g", POSITIVE, NEGATIVE, INT);
        IrSort eqMinus5 = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), IrExpr.lit(-5)));
        StaticDispatch.Result r = StaticDispatch.resolve(
                List.of(f), List.of(EQ_5, eqMinus5));
        assertInstanceOf(StaticDispatch.Result.Resolved.class, r);
    }

    @Test
    void multiArg_oneFails_unresolved() {
        IrStmt.FunctionDecl f = decl2("g", POSITIVE, NEGATIVE, INT);
        // Second arg is also positive (5) → fails NEGATIVE param.
        StaticDispatch.Result r = StaticDispatch.resolve(
                List.of(f), List.of(EQ_5, EQ_5));
        assertInstanceOf(StaticDispatch.Result.Unresolved.class, r);
    }

    @Test
    void arityMismatch_unresolved() {
        IrStmt.FunctionDecl unary = decl("f", INT, INT);
        // Two args provided → arity mismatch.
        StaticDispatch.Result r = StaticDispatch.resolve(
                List.of(unary), List.of(EQ_5, EQ_5));
        assertInstanceOf(StaticDispatch.Result.Unresolved.class, r);
    }

    @Test
    void noOverloads_unresolved() {
        StaticDispatch.Result r = StaticDispatch.resolve(List.of(), List.of(EQ_5));
        assertInstanceOf(StaticDispatch.Result.Unresolved.class, r);
        assertTrue(((StaticDispatch.Result.Unresolved) r).reason().contains("no overloads"));
    }

    // --- Return sort plumbing -----------------------------------------------

    // --- Dependent param substitution (WAR(dependent-sorts) slice 2 (b)) -----

    /** g(x:Int, i:[Int:@<x]) — i's sort depends on the sibling x. */
    private static IrStmt.FunctionDecl gDependent() {
        IrSort depParam = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.LT, IrExpr.self(), IrExpr.var("x")));
        return IrStmt.functionDecl("g",
                List.of(new IrParam("x", IrSort.named("Int")), new IrParam("i", depParam)),
                IrSort.named("Int"), IrExpr.lit(0));
    }

    private static IrSort eq(long v) {
        return IrSort.refined("Int", IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), IrExpr.lit(v)));
    }

    @Test
    void dependentParam_substitutedSibling_provableFail_classifiesFailed() {
        // g(5, 7): substitute x↦5 ⇒ i:[Int:@<5]; 7 ⊀ 5 ⇒ provable FAILED (the §0 hole).
        assertEquals(StaticDispatch.Verdict.FAILED,
                StaticDispatch.classify(List.of(gDependent()), List.of(EQ_5, eq(7))));
    }

    @Test
    void dependentParam_substitutedSibling_provablePass_classifiesPassed() {
        // g(5, 3): x↦5 ⇒ i:[Int:@<5]; 3 < 5 ⇒ PASSED.
        assertEquals(StaticDispatch.Verdict.PASSED,
                StaticDispatch.classify(List.of(gDependent()), List.of(EQ_5, eq(3))));
    }

    @Test
    void dependentParam_unpinnedSibling_staysResidual() {
        // x's arg is [Int:@>0], not a singleton ⇒ no value to substitute for x ⇒
        // i:[Int:@<x] can't be decided ⇒ RESIDUAL (we never invent a value).
        assertEquals(StaticDispatch.Verdict.RESIDUAL,
                StaticDispatch.classify(List.of(gDependent()), List.of(POSITIVE, eq(7))));
    }

    // --- Gate disjointness: FAILED ⟺ provably disjoint, not subset-failure --------

    private static final IrSort GE_0 = IrSort.refined("Int",
            IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(0)));

    @Test
    void rangeArgOverlappingParam_isResidualNotFailed() {
        // f(x:[Int:@>0]) with arg narrowed to [Int:@>=0]: not a subset (0 ∉ @>0)
        // but they OVERLAP (all positives) → undecided, NOT a provable misroute.
        // (Before disjoint-based FAILED this wrongly classified FAILED — the bug
        // that broke multi-overload recursion when args were bounded to ranges.)
        IrStmt.FunctionDecl f = decl("f", POSITIVE, IrSort.named("Int"));
        assertEquals(StaticDispatch.Verdict.RESIDUAL,
                StaticDispatch.classify(List.of(f), List.of(GE_0)));
    }

    @Test
    void rangeArgDisjointFromParam_isFailed() {
        // f(x:[Int:@>0]) with arg [Int:@<0]: empty intersection → provably misroutes.
        IrStmt.FunctionDecl f = decl("f", POSITIVE, IrSort.named("Int"));
        assertEquals(StaticDispatch.Verdict.FAILED,
                StaticDispatch.classify(List.of(f), List.of(NEGATIVE)));
    }

    @Test
    void resolved_exposesDeclaredReturnSort() {
        IrSort returnSort = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(1)));
        IrStmt.FunctionDecl f = decl("factorial", IrSort.named("Int"), returnSort);

        StaticDispatch.Result r = StaticDispatch.resolve(List.of(f), List.of(EQ_5));
        assertInstanceOf(StaticDispatch.Result.Resolved.class, r);
        assertEquals(returnSort, ((StaticDispatch.Result.Resolved) r).returnSort());
    }
}
