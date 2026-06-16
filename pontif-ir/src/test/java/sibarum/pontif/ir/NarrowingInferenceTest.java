package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;

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

    @Test
    void decimalLiteral_narrowsToSingleton() {
        // A decimal literal's value is exact, so it narrows to [Decimal:@==v]
        // just like an integer literal (no bound engine involved).
        java.math.BigDecimal v = new java.math.BigDecimal("1.2");
        IrSort result = NarrowingInference.infer(IrExpr.dec(v), InferenceContext.empty());
        assertEquals(decEq(v), result);
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

    // --- Arithmetic narrowing (linear bounds) --------------------------------

    @Test
    void binOp_constantFolds() {
        // 1 + 2 → [Int:@==3]
        IrSort result = NarrowingInference.infer(
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.lit(1), IrExpr.lit(2)),
                InferenceContext.empty());
        assertEquals(intEq(3), result);
    }

    @Test
    void binOp_narrowsSumUnderLowerBound() {
        // x + 1 with x:[Int:@>=1] → [Int:@>=2]
        IrSort result = NarrowingInference.infer(
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.lit(1)),
                InferenceContext.of(Map.of("x", intGe(1))));
        assertEquals(intGe(2), result);
    }

    @Test
    void binOp_narrowsScaledVar() {
        // 2 * x with x:[Int:@>=1] → [Int:@>=2]
        IrSort result = NarrowingInference.infer(
                IrExpr.binOp(IrExpr.Op.MUL, IrExpr.lit(2), IrExpr.var("x")),
                InferenceContext.of(Map.of("x", intGe(1))));
        assertEquals(intGe(2), result);
    }

    @Test
    void binOp_narrowsSubtraction() {
        // x - 1 with x:[Int:@>=1] → [Int:@>=0]
        IrSort result = NarrowingInference.infer(
                IrExpr.binOp(IrExpr.Op.SUB, IrExpr.var("x"), IrExpr.lit(1)),
                InferenceContext.of(Map.of("x", intGe(1))));
        assertEquals(intGe(0), result);
    }

    @Test
    void binOp_narrowsSquareViaSign() {
        // x * x with no constraint on x → [Int:@>=0] (square is non-negative)
        IrSort result = NarrowingInference.infer(
                IrExpr.binOp(IrExpr.Op.MUL, IrExpr.var("x"), IrExpr.var("x")),
                InferenceContext.empty());
        assertEquals(intGe(0), result);
    }

    @Test
    void binOp_narrowsFiniteRange() {
        // x + 1 with x:[Int:@>=1 & @<=4] → [Int:@>=2 & @<=5]
        IrSort result = NarrowingInference.infer(
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.lit(1)),
                InferenceContext.of(Map.of("x", intRange(1, 4))));
        assertEquals(intRange(2, 5), result);
    }

    @Test
    void inferFunctionReturn_narrowsArithmeticBody() {
        // function f(x:[Int:@>=1]):Int -> x + 1   infers return [Int:@>=2]
        IrStmt.FunctionDecl fd = IrStmt.functionDecl(
                "f", List.of(new IrParam("x", intGe(1))), IrSort.named("Int"),
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.lit(1)));
        IrSort inferred = NarrowingInference.inferFunctionReturn(fd, InferenceContext.empty());
        assertEquals(intGe(2), inferred);
    }

    // --- Arithmetic narrowing: cases that stay null --------------------------

    @Test
    void binOp_unconstrainedVar_returnsNull() {
        // x + 1 with nothing known about x → no narrowing
        assertNull(NarrowingInference.infer(
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.lit(1)),
                InferenceContext.empty()));
    }

    @Test
    void binOp_comparisonOp_returnsNull() {
        // x > 0 yields a Bool, not a bounded Int — not bound-analysis territory
        assertNull(NarrowingInference.infer(
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.var("x"), IrExpr.lit(0)),
                InferenceContext.of(Map.of("x", intGe(1)))));
    }

    // --- Out-of-scope expressions return null (Phase A contract) -------------

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

    // --- Iteration construct (docs/iteration.md) -----------------------------

    /**
     * map: one default stream, the arm transforming the element. The element
     * is narrowed by the arm pattern ({@code [@>=0]}), so {@code e + 1} narrows
     * to {@code [Int:@>=1]} and the result is {@code Stream[Int:@>=1]} — the
     * element-quantified narrowing (∀ element ⟹ stream-of-refined).
     */
    @Test
    void iterate_map_narrowsToStreamOfTransformedElement() {
        IrExpr.Arm arm = new IrExpr.Arm(
                intGe(0),
                List.of(new IrExpr.Write("default", null,
                        IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("e"), IrExpr.lit(1)))));
        IrExpr.Iterate it = new IrExpr.Iterate(
                IrExpr.var("xs"), "e",
                List.of(new IrExpr.OutputSpec("default", IrExpr.OutputKind.STREAM, null)),
                List.of(arm), Origin.NONE);

        IrSort result = NarrowingInference.infer(it, InferenceContext.empty());
        assertEquals(new IrSort.Named("Stream", List.of(intGe(1)), Origin.NONE), result);
    }

    /**
     * filter: two streams, each arm placing the element verbatim into one. Each
     * stream's element sort lifts the routing arm's pattern, so the completed
     * result is the anonymous record {@code {accept: Stream[Int:@>0],
     * reject: Stream[Int:@<=0]}} (mirrors evalIterate's multi-output seal).
     */
    @Test
    void iterate_filter_narrowsToRecordOfRefinedStreams() {
        IrSort pos = IrSort.refined("Int", IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        IrSort nonPos = IrSort.refined("Int", IrExpr.binOp(IrExpr.Op.LE, IrExpr.self(), IrExpr.lit(0)));
        IrExpr.Iterate it = new IrExpr.Iterate(
                IrExpr.var("xs"), "e",
                List.of(new IrExpr.OutputSpec("accept", IrExpr.OutputKind.STREAM, null),
                        new IrExpr.OutputSpec("reject", IrExpr.OutputKind.STREAM, null)),
                List.of(
                        new IrExpr.Arm(pos, List.of(new IrExpr.Write("accept", null, IrExpr.var("e")))),
                        new IrExpr.Arm(nonPos, List.of(new IrExpr.Write("reject", null, IrExpr.var("e"))))),
                Origin.NONE);

        Map<String, IrSort> expectedMembers = new java.util.LinkedHashMap<>();
        expectedMembers.put("accept", new IrSort.Named("Stream", List.of(pos), Origin.NONE));
        expectedMembers.put("reject", new IrSort.Named("Stream", List.of(nonPos), Origin.NONE));
        assertEquals(IrSort.structural("_record", expectedMembers),
                NarrowingInference.infer(it, InferenceContext.empty()));
    }

    /**
     * An un-narrowable written value (an opaque call) still types the result as
     * a {@code Stream} — never {@code _}; the element type is simply unknown.
     */
    @Test
    void iterate_unknownElement_narrowsToBareStream() {
        IrExpr.Arm arm = new IrExpr.Arm(
                IrSort.named("Int"),
                List.of(new IrExpr.Write("default", null, IrExpr.call("opaque", List.of()))));
        IrExpr.Iterate it = new IrExpr.Iterate(
                IrExpr.var("xs"), "e",
                List.of(new IrExpr.OutputSpec("default", IrExpr.OutputKind.STREAM, null)),
                List.of(arm), Origin.NONE);

        assertEquals(new IrSort.Named("Stream", List.of(), Origin.NONE),
                NarrowingInference.infer(it, InferenceContext.empty()));
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

    /** {@code [Decimal:@==v]} */
    private static IrSort decEq(java.math.BigDecimal v) {
        return IrSort.refined("Decimal", eqSelf(IrExpr.dec(v)));
    }

    /** {@code [Int:@>=n]} */
    private static IrSort intGe(long n) {
        return IrSort.refined("Int", IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(n)));
    }

    /** {@code [Int:@>=lo & @<=hi]} */
    private static IrSort intRange(long lo, long hi) {
        return IrSort.refined("Int", IrExpr.binOp(
                IrExpr.Op.AND,
                IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(lo)),
                IrExpr.binOp(IrExpr.Op.LE, IrExpr.self(), IrExpr.lit(hi))));
    }

    /** {@code @ == value} as an {@link IrExpr}. */
    private static IrExpr eqSelf(IrExpr value) {
        return IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), value);
    }
}
