package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase A coverage tests for {@link NarrowingInference}: literals,
 * variables, let-bindings, calls (fallback), and the headline
 * match-arm result narrowing slice.
 *
 * <p>(Phase B exercises struct refinement plumbing via
 * {@code StructRefinementTest}; Phase C exercises record literal,
 * field-access, and struct match-arm narrowing via
 * {@code StructNarrowingTest}.)
 */
class NarrowingInferenceTest {

    // --- Literals ------------------------------------------------------------

    @Test
    void intLiteral_narrowsToSingleton() {
        IrSort result = NarrowingInference.infer(IrExpr.lit(3), InferenceContext.empty());
        assertEquals(intEq(3), result);
    }

    @Test
    void boolLiteral_narrowsToSingleton() {
        IrSort result = NarrowingInference.infer(IrExpr.bool(true), InferenceContext.empty());
        assertEquals(boolEq(true), result);
    }

    // --- Var lookup ----------------------------------------------------------

    @Test
    void varInEnv_returnsBoundSort() {
        IrSort xSort = IrSort.refined(
                "Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        IrSort result = NarrowingInference.infer(
                IrExpr.var("x"), InferenceContext.of(Map.of("x", xSort)));
        assertEquals(xSort, result);
    }

    @Test
    void varNotInEnv_returnsNull() {
        IrSort result = NarrowingInference.infer(
                IrExpr.var("missing"), InferenceContext.empty());
        assertNull(result);
    }

    // --- Match: the headline slice -------------------------------------------

    /**
     * The {@code sign(n)} example from the phase A plan:
     * <pre>
     *   match n
     *     [@&lt;0]  -&gt; -1
     *     [@==0] -&gt; 0
     *     [@&gt;0]  -&gt; 1
     * </pre>
     * Inferred return narrowing: {@code [Int:@==-1 | @==0 | @==1]} — the
     * same-base union of the three arm result narrowings.
     */
    @Test
    void match_signExample_unionsLiteralArmsIntoSameBaseRefinement() {
        IrExpr body = IrExpr.match(
                IrExpr.var("n"),
                List.of(
                        IrExpr.matchBranch(
                                IrSort.refined("Int",
                                        IrExpr.binOp(IrExpr.Op.LT, IrExpr.self(), IrExpr.lit(0))),
                                IrExpr.lit(-1)),
                        IrExpr.matchBranch(
                                IrSort.refined("Int",
                                        IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), IrExpr.lit(0))),
                                IrExpr.lit(0)),
                        IrExpr.matchBranch(
                                IrSort.refined("Int",
                                        IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0))),
                                IrExpr.lit(1))));

        IrSort result = NarrowingInference.infer(
                body, InferenceContext.of(Map.of("n", IrSort.named("Int"))));

        IrExpr expectedPredicate = IrExpr.binOp(
                IrExpr.Op.OR,
                IrExpr.binOp(
                        IrExpr.Op.OR,
                        eqSelf(IrExpr.lit(-1)),
                        eqSelf(IrExpr.lit(0))),
                eqSelf(IrExpr.lit(1)));
        IrSort expected = IrSort.refined("Int", expectedPredicate);

        assertEquals(expected, result);
    }

    @Test
    void match_singleArm_returnsArmNarrowingWithoutUnioning() {
        IrExpr body = IrExpr.match(
                IrExpr.var("n"),
                List.of(IrExpr.matchBranch(
                        IrSort.named("Int"),
                        IrExpr.lit(42))));

        IrSort result = NarrowingInference.infer(
                body, InferenceContext.of(Map.of("n", IrSort.named("Int"))));

        assertEquals(intEq(42), result);
    }

    /**
     * When any arm's result narrowing can't be inferred, the whole match
     * conservatively returns {@code null} — we don't widen by claiming the
     * inferrable arms cover the result.
     */
    @Test
    void match_armWithUnknownNarrowing_propagatesNull() {
        IrExpr body = IrExpr.match(
                IrExpr.var("n"),
                List.of(
                        IrExpr.matchBranch(IrSort.named("Int"), IrExpr.lit(1)),
                        // BinOp not inferred in Phase A → arm returns null.
                        IrExpr.matchBranch(
                                IrSort.named("Int"),
                                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("n"), IrExpr.lit(1)))));

        IrSort result = NarrowingInference.infer(
                body, InferenceContext.of(Map.of("n", IrSort.named("Int"))));

        assertNull(result);
    }

    /**
     * Match arms whose scrutinee is a {@link IrExpr.Var} narrow that var
     * inside the arm body to the arm's pattern — mirrors
     * {@link SortChecker}'s existing Var-scrutinee narrowing scope.
     */
    @Test
    void match_armBodyVarReference_seesPatternNarrowing() {
        IrSort negPattern = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.LT, IrExpr.self(), IrExpr.lit(0)));
        IrSort posPattern = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(0)));

        IrExpr body = IrExpr.match(
                IrExpr.var("n"),
                List.of(
                        IrExpr.matchBranch(negPattern, IrExpr.var("n")),
                        IrExpr.matchBranch(posPattern, IrExpr.var("n"))));

        IrSort result = NarrowingInference.infer(
                body, InferenceContext.of(Map.of("n", IrSort.named("Int"))));

        IrExpr expectedPredicate = IrExpr.binOp(
                IrExpr.Op.OR,
                IrExpr.binOp(IrExpr.Op.LT, IrExpr.self(), IrExpr.lit(0)),
                IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(0)));
        assertEquals(IrSort.refined("Int", expectedPredicate), result);
    }

    // --- LetIn ---------------------------------------------------------------

    @Test
    void letIn_extendsEnvWithValueNarrowing() {
        IrExpr body = IrExpr.letIn(
                "x", IrSort.named("Int"),
                IrExpr.lit(5),
                IrExpr.var("x"));

        IrSort result = NarrowingInference.infer(body, InferenceContext.empty());
        assertEquals(intEq(5), result);
    }

    @Test
    void letIn_unknownValueFallsBackToDeclaredSortForVar() {
        IrSort declared = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        IrExpr body = IrExpr.letIn(
                "x", declared,
                IrExpr.call("opaque", List.of()),
                IrExpr.var("x"));

        IrSort result = NarrowingInference.infer(body, InferenceContext.empty());
        assertEquals(declared, result);
    }

    // --- Call ----------------------------------------------------------------

    @Test
    void call_returnsDeclaredReturnSort() {
        IrSort declaredReturn = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(1)));

        IrSort result = NarrowingInference.infer(
                IrExpr.call("factorial", List.of(IrExpr.lit(3))),
                InferenceContext.of(Map.of(), Map.of("factorial", declaredReturn)));

        assertEquals(declaredReturn, result);
    }

    @Test
    void call_unknownFunctionReturnsNull() {
        IrSort result = NarrowingInference.infer(
                IrExpr.call("notDeclared", List.of()), InferenceContext.empty());
        assertNull(result);
    }

    // --- inferFunctionReturn convenience -------------------------------------

    @Test
    void inferFunctionReturn_seedsEnvFromParams() {
        IrStmt.FunctionDecl fd = IrStmt.functionDecl(
                "sign",
                List.of(new IrParam("n", IrSort.named("Int"))),
                IrSort.named("Int"),
                IrExpr.match(
                        IrExpr.var("n"),
                        List.of(
                                IrExpr.matchBranch(
                                        IrSort.refined("Int",
                                                IrExpr.binOp(IrExpr.Op.LT, IrExpr.self(), IrExpr.lit(0))),
                                        IrExpr.lit(-1)),
                                IrExpr.matchBranch(
                                        IrSort.refined("Int",
                                                IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), IrExpr.lit(0))),
                                        IrExpr.lit(0)),
                                IrExpr.matchBranch(
                                        IrSort.refined("Int",
                                                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0))),
                                        IrExpr.lit(1)))));

        IrSort inferred = NarrowingInference.inferFunctionReturn(fd, InferenceContext.empty());

        assertTrue(inferred instanceof IrSort.Refined,
                () -> "Expected Refined, got " + inferred);
        IrSort.Refined r = (IrSort.Refined) inferred;
        assertEquals("Int", r.name());
    }

    // --- Out-of-scope expressions return null (Phase A contract) -------------

    @Test
    void binOp_returnsNullForNow() {
        assertNull(NarrowingInference.infer(
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.lit(1), IrExpr.lit(2)),
                InferenceContext.empty()));
    }

    @Test
    void anonymousRecord_returnsNull() {
        // Phase C narrows nominally-typed records; anonymous records stay null.
        assertNull(NarrowingInference.infer(
                IrExpr.record(new java.util.LinkedHashMap<>(Map.of(
                        "x", IrExpr.lit(1), "y", IrExpr.lit(2)))),
                InferenceContext.empty()));
    }

    @Test
    void fieldAccess_returnsNullWithoutStructDef() {
        // Phase C requires structDefs to project fields. Without it, null.
        assertNull(NarrowingInference.infer(
                IrExpr.fieldAccess(IrExpr.var("p"), "x"),
                InferenceContext.of(Map.of("p", IrSort.structural("Point",
                        Map.of("x", IrSort.named("Int"), "y", IrSort.named("Int")))))));
    }

    // --- Helpers -------------------------------------------------------------

    /** {@code [Int:@==n]} */
    private static IrSort intEq(long n) {
        return IrSort.refined("Int", eqSelf(IrExpr.lit(n)));
    }

    /** {@code [Bool:@==b]} */
    private static IrSort boolEq(boolean b) {
        return IrSort.refined("Bool", eqSelf(IrExpr.bool(b)));
    }

    /** {@code @ == value} as an {@link IrExpr}. */
    private static IrExpr eqSelf(IrExpr value) {
        return IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), value);
    }
}
