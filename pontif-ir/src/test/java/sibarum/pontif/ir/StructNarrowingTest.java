package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase C coverage: struct-narrowing unifier — record-literal narrowing,
 * field-access projection from narrowed records, and struct match-arm
 * hypothesis derivation tying A and B together.
 */
class StructNarrowingTest {

    /** {@code struct Point(x:Int, y:Int)} as the canonical fixture. */
    private static final IrSort.Structural POINT = (IrSort.Structural)
            IrSort.structural("Point", orderedMembers("x", "y"));

    private static Map<String, IrSort> orderedMembers(String... names) {
        Map<String, IrSort> m = new LinkedHashMap<>();
        for (String n : names) m.put(n, IrSort.named("Int"));
        return m;
    }

    private static InferenceContext ctxWithPoint() {
        return InferenceContext.empty().withStructDefs(Map.of("Point", POINT));
    }

    // --- Record literal narrowing -------------------------------------------

    @Test
    void recordLiteral_concreteFields_narrowsToConjunctedRefinement() {
        // Point(3, 4) → [Point: @.x == 3 & @.y == 4]
        IrExpr point34 = IrExpr.record("Point",
                ordered("x", IrExpr.lit(3), "y", IrExpr.lit(4)));

        IrSort result = NarrowingInference.infer(point34, ctxWithPoint());

        IrExpr expected = and(
                eq(fieldOfSelf("x"), IrExpr.lit(3)),
                eq(fieldOfSelf("y"), IrExpr.lit(4)));
        assertEquals(IrSort.refined("Point", expected), result);
    }

    @Test
    void recordLiteral_oneSymbolicField_narrowsOnlyTheKnownField() {
        // Point(3, n) where n is unknown → [Point: @.x == 3]
        IrExpr point3n = IrExpr.record("Point",
                ordered("x", IrExpr.lit(3), "y", IrExpr.var("n")));

        IrSort result = NarrowingInference.infer(point3n, ctxWithPoint());

        // n's narrowing is null (not in env), so only the x conjunct survives.
        assertEquals(
                IrSort.refined("Point", eq(fieldOfSelf("x"), IrExpr.lit(3))),
                result);
    }

    @Test
    void recordLiteral_allSymbolic_returnsNull() {
        IrExpr point = IrExpr.record("Point",
                ordered("x", IrExpr.var("a"), "y", IrExpr.var("b")));

        assertNull(NarrowingInference.infer(point, ctxWithPoint()));
    }

    @Test
    void anonymousRecord_returnsNullEvenWithConcreteFields() {
        // No typeName → no nominal target → null
        IrExpr anon = IrExpr.record(ordered("x", IrExpr.lit(3), "y", IrExpr.lit(4)));
        assertNull(NarrowingInference.infer(anon, ctxWithPoint()));
    }

    // --- Field access narrowing ---------------------------------------------

    @Test
    void fieldAccess_onNarrowedRecord_projectsFieldSingleton() {
        // p : [Point: @.x == 3 & @.y == 4]; p.x → [Int:@==3]
        IrSort pSort = IrSort.refined("Point", and(
                eq(fieldOfSelf("x"), IrExpr.lit(3)),
                eq(fieldOfSelf("y"), IrExpr.lit(4))));
        InferenceContext ctx = ctxWithPoint().withVar("p", pSort);

        IrSort result = NarrowingInference.infer(
                IrExpr.fieldAccess(IrExpr.var("p"), "x"), ctx);

        assertEquals(IrSort.refined("Int", eq(IrExpr.self(), IrExpr.lit(3))), result);
    }

    @Test
    void fieldAccess_onPartiallyNarrowedRecord_projectsKnownField() {
        // p : [Point: @.x > 0]; p.x → [Int:@>0]
        IrSort pSort = IrSort.refined("Point",
                IrExpr.binOp(IrExpr.Op.GT, fieldOfSelf("x"), IrExpr.lit(0)));
        InferenceContext ctx = ctxWithPoint().withVar("p", pSort);

        IrSort result = NarrowingInference.infer(
                IrExpr.fieldAccess(IrExpr.var("p"), "x"), ctx);

        assertEquals(
                IrSort.refined("Int",
                        IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0))),
                result);
    }

    @Test
    void fieldAccess_crossFieldConjunct_skippedConservatively() {
        // p : [Point: @.x + @.y > 0]; p.x → null (cross-field; can't decompose)
        IrSort pSort = IrSort.refined("Point",
                IrExpr.binOp(IrExpr.Op.GT,
                        IrExpr.binOp(IrExpr.Op.ADD, fieldOfSelf("x"), fieldOfSelf("y")),
                        IrExpr.lit(0)));
        InferenceContext ctx = ctxWithPoint().withVar("p", pSort);

        assertNull(NarrowingInference.infer(
                IrExpr.fieldAccess(IrExpr.var("p"), "x"), ctx));
    }

    @Test
    void fieldAccess_mixedConjuncts_projectsOnlySingleFieldOnes() {
        // p : [Point: @.x > 0 & @.x + @.y > 0]; p.x → [Int:@>0]
        // (only the single-field conjunct survives)
        IrSort pSort = IrSort.refined("Point", and(
                IrExpr.binOp(IrExpr.Op.GT, fieldOfSelf("x"), IrExpr.lit(0)),
                IrExpr.binOp(IrExpr.Op.GT,
                        IrExpr.binOp(IrExpr.Op.ADD, fieldOfSelf("x"), fieldOfSelf("y")),
                        IrExpr.lit(0))));
        InferenceContext ctx = ctxWithPoint().withVar("p", pSort);

        IrSort result = NarrowingInference.infer(
                IrExpr.fieldAccess(IrExpr.var("p"), "x"), ctx);

        assertEquals(
                IrSort.refined("Int",
                        IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0))),
                result);
    }

    @Test
    void fieldAccess_withoutStructDefs_returnsNull() {
        // Same narrowing, but no struct registered → can't determine field's base.
        IrSort pSort = IrSort.refined("Point",
                eq(fieldOfSelf("x"), IrExpr.lit(3)));
        InferenceContext ctx = InferenceContext.empty().withVar("p", pSort);

        assertNull(NarrowingInference.infer(
                IrExpr.fieldAccess(IrExpr.var("p"), "x"), ctx));
    }

    @Test
    void fieldAccess_onRecordLiteral_combinesA_and_B() {
        // Point(3, 4).x → [Int:@==3] — record narrowing + field projection.
        IrExpr expr = IrExpr.fieldAccess(
                IrExpr.record("Point", ordered("x", IrExpr.lit(3), "y", IrExpr.lit(4))),
                "x");

        IrSort result = NarrowingInference.infer(expr, ctxWithPoint());

        assertEquals(IrSort.refined("Int", eq(IrExpr.self(), IrExpr.lit(3))), result);
    }

    // --- Intersection member resolution (the some-branch rule) --------------

    /**
     * A field access on an intersection base resolves off whichever branch
     * declares the field — {@code [A & B]} carries A's members and B's. This is
     * the precondition for AlgebraicDispatch's {@code [Dispatch & Algebraic]}.
     */
    @Test
    void fieldAccess_onIntersection_resolvesFromTheBranchThatHasIt() {
        IrSort.Structural a = (IrSort.Structural) IrSort.structural("A", orderedMembers("a"));
        IrSort.Structural b = (IrSort.Structural) IrSort.structural("B", orderedMembers("b"));
        InferenceContext ctx = InferenceContext
                .of(Map.of("x", intersection(IrSort.named("A"), IrSort.named("B"))))
                .withStructDefs(Map.of("A", a, "B", b));

        assertEquals(IrSort.named("Int"),
                NarrowingInference.infer(IrExpr.fieldAccess(IrExpr.var("x"), "a"), ctx));
        assertEquals(IrSort.named("Int"),
                NarrowingInference.infer(IrExpr.fieldAccess(IrExpr.var("x"), "b"), ctx));
    }

    @Test
    void fieldAccess_onIntersection_absentOnEveryBranch_returnsNull() {
        IrSort.Structural a = (IrSort.Structural) IrSort.structural("A", orderedMembers("a"));
        IrSort.Structural b = (IrSort.Structural) IrSort.structural("B", orderedMembers("b"));
        InferenceContext ctx = InferenceContext
                .of(Map.of("x", intersection(IrSort.named("A"), IrSort.named("B"))))
                .withStructDefs(Map.of("A", a, "B", b));

        assertNull(NarrowingInference.infer(IrExpr.fieldAccess(IrExpr.var("x"), "nope"), ctx));
    }

    @Test
    void fieldAccess_onIntersection_conflictingBranchesAbstainToNull() {
        // Both branches declare `v` but at different sorts → the projection can't
        // pick one, so it abstains (the SortChecker gate reports the ambiguity).
        Map<String, IrSort> aMembers = new LinkedHashMap<>();
        aMembers.put("v", IrSort.named("Int"));
        Map<String, IrSort> bMembers = new LinkedHashMap<>();
        bMembers.put("v", IrSort.named("String"));
        IrSort.Structural a = (IrSort.Structural) IrSort.structural("A", aMembers);
        IrSort.Structural b = (IrSort.Structural) IrSort.structural("B", bMembers);
        InferenceContext ctx = InferenceContext
                .of(Map.of("x", intersection(IrSort.named("A"), IrSort.named("B"))))
                .withStructDefs(Map.of("A", a, "B", b));

        assertNull(NarrowingInference.infer(IrExpr.fieldAccess(IrExpr.var("x"), "v"), ctx));
    }

    private static IrSort intersection(IrSort... branches) {
        return new IrSort.Intersection(List.of(branches), sibarum.pontif.core.Origin.NONE);
    }

    // --- Struct match-arm narrowing (the headline) --------------------------

    /**
     * The unifier — struct match arm narrows the scrutinee Var to the
     * arm pattern, then a field access on it projects out the per-field
     * narrowing.
     *
     * <pre>
     *   match p
     *     [Point:@.x > 0] -> p.x   # ⇒ [Int:@>0]
     *     [Point:@.x < 0] -> p.x   # ⇒ [Int:@<0]
     * </pre>
     *
     * Both arms narrow {@code p.x} to a same-base refinement; union →
     * {@code [Int:@>0 | @<0]}.
     */
    @Test
    void structMatchArmNarrowing_projectedFieldNarrowingFlowsThroughUnion() {
        IrSort positiveX = IrSort.refined("Point",
                IrExpr.binOp(IrExpr.Op.GT, fieldOfSelf("x"), IrExpr.lit(0)));
        IrSort negativeX = IrSort.refined("Point",
                IrExpr.binOp(IrExpr.Op.LT, fieldOfSelf("x"), IrExpr.lit(0)));

        IrExpr body = IrExpr.match(
                IrExpr.var("p"),
                List.of(
                        IrExpr.matchBranch(positiveX,
                                IrExpr.fieldAccess(IrExpr.var("p"), "x")),
                        IrExpr.matchBranch(negativeX,
                                IrExpr.fieldAccess(IrExpr.var("p"), "x"))));

        InferenceContext ctx = ctxWithPoint().withVar("p", IrSort.named("Point"));
        IrSort result = NarrowingInference.infer(body, ctx);

        IrExpr expectedPred = IrExpr.binOp(
                IrExpr.Op.OR,
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)),
                IrExpr.binOp(IrExpr.Op.LT, IrExpr.self(), IrExpr.lit(0)));
        assertEquals(IrSort.refined("Int", expectedPred), result);
    }

    @Test
    void structMatchArmNarrowing_inferFunctionReturn_endToEnd() {
        // function checkX(p:Point):? -> match p
        //   [Point:@.x > 0] -> p.x
        //   [Point:@.x == 0] -> p.x
        //   [Point:@.x < 0] -> p.x
        //
        // Expected return narrowing: [Int: @>0 | @==0 | @<0]
        IrStmt.FunctionDecl fd = IrStmt.functionDecl(
                "checkX",
                List.of(new IrParam("p", IrSort.named("Point"))),
                IrSort.named("Int"),
                IrExpr.match(
                        IrExpr.var("p"),
                        List.of(
                                IrExpr.matchBranch(
                                        IrSort.refined("Point",
                                                IrExpr.binOp(IrExpr.Op.GT, fieldOfSelf("x"), IrExpr.lit(0))),
                                        IrExpr.fieldAccess(IrExpr.var("p"), "x")),
                                IrExpr.matchBranch(
                                        IrSort.refined("Point",
                                                IrExpr.binOp(IrExpr.Op.EQ, fieldOfSelf("x"), IrExpr.lit(0))),
                                        IrExpr.fieldAccess(IrExpr.var("p"), "x")),
                                IrExpr.matchBranch(
                                        IrSort.refined("Point",
                                                IrExpr.binOp(IrExpr.Op.LT, fieldOfSelf("x"), IrExpr.lit(0))),
                                        IrExpr.fieldAccess(IrExpr.var("p"), "x")))));

        IrSort inferred = NarrowingInference.inferFunctionReturn(fd, ctxWithPoint());

        assertNotNull(inferred);
        assertTrue(inferred instanceof IrSort.Refined,
                () -> "Expected Refined, got " + inferred);
        assertEquals("Int", ((IrSort.Refined) inferred).name());
    }

    // --- Helpers -------------------------------------------------------------

    private static Map<String, IrExpr> ordered(String k1, IrExpr v1, String k2, IrExpr v2) {
        Map<String, IrExpr> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    private static Map<String, IrExpr> ordered(String k1, IrExpr v1) {
        Map<String, IrExpr> m = new LinkedHashMap<>();
        m.put(k1, v1);
        return m;
    }

    /** {@code @.field} */
    private static IrExpr fieldOfSelf(String field) {
        return IrExpr.fieldAccess(IrExpr.self(), field);
    }

    /** {@code a == b} */
    private static IrExpr eq(IrExpr l, IrExpr r) {
        return IrExpr.binOp(IrExpr.Op.EQ, l, r);
    }

    /** {@code a & b} */
    private static IrExpr and(IrExpr l, IrExpr r) {
        return IrExpr.binOp(IrExpr.Op.AND, l, r);
    }
}
