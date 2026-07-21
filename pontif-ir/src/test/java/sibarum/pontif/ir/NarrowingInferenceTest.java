package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;

import java.util.List;
import java.util.Map;
import java.util.Set;

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

    // --- Effective (accumulated) sort ---------------------------------------

    @Test
    void effectiveSort_projectsUseSiteBoundUnderHypotheses() {
        // docs/type-records.md's own example: n - 1 under n:[Int:@>0] accumulates to [Int:@>=0].
        // infer alone yields only the raw pin [Int:@==n-1]; effectiveSort projects it at the use site.
        IrSort nGt0 = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        InferenceContext ctx = InferenceContext.of(Map.of("n", nGt0));
        IrExpr nMinus1 = IrExpr.binOp(IrExpr.Op.SUB, IrExpr.var("n"), IrExpr.lit(1));
        assertEquals(intPin(nMinus1), NarrowingInference.infer(nMinus1, ctx));
        assertEquals(intGe(0), NarrowingInference.effectiveSort(nMinus1, ctx));
    }

    @Test
    void effectiveSort_unboundedProjection_fallsToBareBase() {
        // a + b, both bare Int: nothing bounds the sum → the effective sort is bare Int,
        // never a pin leaking the free variables a, b into the consuming scope.
        InferenceContext ctx = InferenceContext.of(Map.of(
                "a", IrSort.named("Int"), "b", IrSort.named("Int")));
        IrExpr aPlusB = IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("a"), IrExpr.var("b"));
        assertEquals(IrSort.named("Int"), NarrowingInference.effectiveSort(aPlusB, ctx));
    }

    @Test
    void effectiveSort_composesFieldInvariantWithGuard() {
        // Account.balance is [Int:@>=0]. Under a guard n:[Int:@>0], the effective sort of
        // `this.balance + n` composes to [Int:@>=1]: the field invariant participates only as
        // this.balance's own effective sort (no hand-injected hypothesis) — BoundAnalysis sums.
        IrSort.Structural account = new IrSort.Structural(
                "Account", Map.of("balance", intGe(0)), null, Map.of(), Origin.NONE);
        IrSort nGt0 = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        InferenceContext ctx = new InferenceContext(
                Map.of("this", IrSort.named("Account"), "n", nGt0),
                Map.of(), Map.of("Account", account),
                Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), Map.of());
        IrExpr balancePlusN = IrExpr.binOp(IrExpr.Op.ADD,
                new IrExpr.FieldAccess(IrExpr.var("this"), "balance", Origin.NONE),
                IrExpr.var("n"));
        assertEquals(intGe(1), NarrowingInference.effectiveSort(balancePlusN, ctx));
    }

    @Test
    void effectiveSort_usesNarrowedReceiverField_notDeclared() {
        // A narrowed receiver [Account:@.balance>5] tightens this.balance's EFFECTIVE sort to
        // [Int:@>5], so `this.balance + n` (n>0) bounds to [Int:@>=7] — proving the field invariant
        // is the field's effective sort, not the declared [Int:@>=0] (which would give [Int:@>=1]).
        IrSort.Structural account = new IrSort.Structural(
                "Account", Map.of("balance", intGe(0)), null, Map.of(), Origin.NONE);
        IrSort narrowedReceiver = IrSort.refined("Account", IrExpr.binOp(IrExpr.Op.GT,
                new IrExpr.FieldAccess(IrExpr.self(), "balance", Origin.NONE), IrExpr.lit(5)));
        IrSort nGt0 = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        InferenceContext ctx = new InferenceContext(
                Map.of("this", narrowedReceiver, "n", nGt0),
                Map.of(), Map.of("Account", account),
                Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), Map.of());
        IrExpr balancePlusN = IrExpr.binOp(IrExpr.Op.ADD,
                new IrExpr.FieldAccess(IrExpr.var("this"), "balance", Origin.NONE), IrExpr.var("n"));
        assertEquals(intGe(7), NarrowingInference.effectiveSort(balancePlusN, ctx));
    }

    @Test
    void effectiveSorts_recordsProjectedSortKeyedBySpan() {
        // The lens keys each position's span to its EFFECTIVE sort. For `n - 1` under n:[Int:@>0],
        // the BinOp position carries the projected [Int:@>=0] — the same value effectiveSort returns,
        // now materialized per position for downstream gates / an IDE.
        IrSort nGt0 = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        InferenceContext ctx = InferenceContext.of(Map.of("n", nGt0));
        sibarum.pontif.core.Origin at = sibarum.pontif.core.Origin.at("t.ptf", 1, 1);
        IrExpr nMinus1 = new IrExpr.BinOp(IrExpr.Op.SUB, IrExpr.var("n"), IrExpr.lit(1), at);
        Map<sibarum.pontif.core.Origin.Span, IrSort> lens =
                NarrowingInference.effectiveSorts(nMinus1, ctx);
        assertEquals(intGe(0), lens.get(at.span()));
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
    void match_unconstrainedArm_collapsesToBareBase() {
        // One arm narrows ([Int:@==1]); the other returns an unconstrained Int (m).
        // The union must be the bare base Int — NOT `[Int:@==1 | true]` (which a
        // `true`-substituted bare arm used to produce). `X | true ≡ true`.
        IrExpr body = IrExpr.match(
                IrExpr.var("n"),
                List.of(
                        IrExpr.matchBranch(
                                IrSort.refined("Int", IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), IrExpr.lit(1))),
                                IrExpr.lit(1)),
                        IrExpr.matchBranch(
                                IrSort.refined("Int", IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(1))),
                                IrExpr.var("m"))));
        IrSort result = NarrowingInference.infer(
                body, InferenceContext.of(Map.of("n", IrSort.named("Int"), "m", IrSort.named("Int"))));
        assertEquals(IrSort.named("Int"), result);
    }

    @Test
    void match_armWithUnknownNarrowing_propagatesNull() {
        IrExpr body = IrExpr.match(
                IrExpr.var("n"),
                List.of(
                        IrExpr.matchBranch(IrSort.named("Int"), IrExpr.lit(1)),
                        // An opaque call (no overloads/returns) doesn't narrow → arm
                        // returns null. (Arithmetic now PINS, so it's no longer the
                        // un-narrowable case; the null-propagation contract is the same.)
                        IrExpr.matchBranch(
                                IrSort.named("Int"),
                                IrExpr.call("opaque", List.of()))));

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

    // --- Arithmetic narrowing: infer is OPEN (exact value-pins) --------------
    // The narrowest kernel-compilable predicate for arithmetic is the exact
    // value-pin [Int:@==expr]; the numeric BOUND is its projection at a scope
    // boundary (see closeOver / inferFunctionReturn below). docs/inference-unification.md.

    @Test
    void binOp_pinsConstantSum() {
        // 1 + 2 → [Int:@==1+2] (the exact pin; folding to @==3 is the simplifier's job)
        IrExpr sum = IrExpr.binOp(IrExpr.Op.ADD, IrExpr.lit(1), IrExpr.lit(2));
        assertEquals(intPin(sum), NarrowingInference.infer(sum, InferenceContext.empty()));
    }

    @Test
    void binOp_pinsSum() {
        // x + 1 → [Int:@==x+1], whatever is known about x (the bound is a boundary projection)
        IrExpr sum = IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.lit(1));
        assertEquals(intPin(sum),
                NarrowingInference.infer(sum, InferenceContext.of(Map.of("x", intGe(1)))));
    }

    @Test
    void binOp_pinsScaledVar() {
        IrExpr e = IrExpr.binOp(IrExpr.Op.MUL, IrExpr.lit(2), IrExpr.var("x"));
        assertEquals(intPin(e),
                NarrowingInference.infer(e, InferenceContext.of(Map.of("x", intGe(1)))));
    }

    @Test
    void binOp_pinsSquare() {
        IrExpr e = IrExpr.binOp(IrExpr.Op.MUL, IrExpr.var("x"), IrExpr.var("x"));
        assertEquals(intPin(e), NarrowingInference.infer(e, InferenceContext.empty()));
    }

    @Test
    void binOp_pinsUnconstrainedVar() {
        // The pin is available even with nothing known about x — the capability gain
        // over the old bound-only narrowing, which returned null here.
        IrExpr sum = IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.lit(1));
        assertEquals(intPin(sum), NarrowingInference.infer(sum, InferenceContext.empty()));
    }

    @Test
    void binOp_comparisonOp_pinsBool() {
        // x > 0 is Bool-valued and pins to the exact [Bool:@==(x>0)] (the parser's
        // historical narrowing, now the core's — open, closed at boundaries).
        IrExpr cmp = IrExpr.binOp(IrExpr.Op.GT, IrExpr.var("x"), IrExpr.lit(0));
        assertEquals(IrSort.refined("Bool", eqSelf(cmp)),
                NarrowingInference.infer(cmp, InferenceContext.of(Map.of("x", intGe(1)))));
    }

    // --- Closing a pin at a scope boundary projects it to a bound ------------

    @Test
    void closeOver_projectsPinToBound() {
        // [Int:@==x+1] closed over the escaping {x} (x:[@>=1]) → [Int:@>=2]
        IrSort pin = intPin(IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.lit(1)));
        assertEquals(intGe(2),
                NarrowingInference.closeOver(pin, Set.of("x"), InferenceContext.of(Map.of("x", intGe(1)))));
    }

    @Test
    void closeOver_projectsFiniteRange() {
        IrSort pin = intPin(IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.lit(1)));
        assertEquals(intRange(2, 5),
                NarrowingInference.closeOver(pin, Set.of("x"), InferenceContext.of(Map.of("x", intRange(1, 4)))));
    }

    @Test
    void closeOver_projectsSquareViaSign() {
        // x*x closed over {x} (nothing known) → [Int:@>=0] (square is non-negative)
        IrSort pin = intPin(IrExpr.binOp(IrExpr.Op.MUL, IrExpr.var("x"), IrExpr.var("x")));
        assertEquals(intGe(0),
                NarrowingInference.closeOver(pin, Set.of("x"), InferenceContext.empty()));
    }

    @Test
    void closeOver_unboundedEscapingVar_returnsNull() {
        // x+1 with nothing known about the escaping x → no closed bound survives.
        IrSort pin = intPin(IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.lit(1)));
        assertNull(NarrowingInference.closeOver(pin, Set.of("x"), InferenceContext.empty()));
    }

    @Test
    void closeOver_noEscapingVar_keepsNarrowing() {
        // A closed refinement (no escaping var mentioned) is returned unchanged.
        assertEquals(intGe(2),
                NarrowingInference.closeOver(intGe(2), Set.of("x"), InferenceContext.empty()));
    }

    @Test
    void inferFunctionReturn_narrowsArithmeticBody() {
        // function f(x:[Int:@>=1]):Int -> x + 1   closes the body pin to the
        // variable-free return bound [Int:@>=2] (params leave scope at the return).
        IrStmt.FunctionDecl fd = IrStmt.functionDecl(
                "f", List.of(new IrParam("x", intGe(1))), IrSort.named("Int"),
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.lit(1)));
        IrSort inferred = NarrowingInference.inferFunctionReturn(fd, InferenceContext.empty());
        assertEquals(intGe(2), inferred);
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

    // --- Metareference stamping: the concrete dispatch nominal (E2) -----------

    @Test
    void dispatchRef_narrowsToPlainDispatchWhenNotAlgebraic() {
        // A plain metareference narrows to the builtin nominal Dispatch — a dispatch-style
        // call sig with no `.ast` (docs/dispatch-method-elimination.md E2). Only an algebraic
        // reference gets the distinct AlgebraicDispatch nominal (below).
        IrExpr.DispatchRef ref = new IrExpr.DispatchRef(
                "f", List.of(IrSort.named("Decimal")), Origin.NONE);
        IrSort result = NarrowingInference.infer(ref, InferenceContext.empty());
        assertEquals(
                new IrSort.CallSig("Dispatch",
                        List.of(IrSort.named("Decimal")), IrSort.named("_"), Origin.NONE),
                result);
    }

    @Test
    void dispatchRef_narrowsToAlgebraicDispatchWhenClaimed() {
        // `poly` carries an `assign proof poly:Algebraic` claim → its metareference is
        // stamped with the concrete nominal AlgebraicDispatch (is-a Algebraic), off which
        // the `.ast` surface resolves. Non-claimed names stay DispatchBase (above).
        IrExpr.DispatchRef ref = new IrExpr.DispatchRef(
                "poly", List.of(IrSort.named("Decimal")), Origin.NONE);
        InferenceContext ctx = new InferenceContext(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Set.of(), Set.of("poly"), Map.of());

        IrSort result = NarrowingInference.infer(ref, ctx);

        assertEquals(
                new IrSort.CallSig("AlgebraicDispatch",
                        List.of(IrSort.named("Decimal")), IrSort.named("_"), Origin.NONE),
                result);
    }

    // --- Floor layer (inferFloor) --------------------------------------------

    @Test
    void inferFloor_passesThroughNarrowing() {
        // When infer has a narrowing, the floor returns it verbatim.
        IrSort xSort = IrSort.refined(
                "Int", IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        InferenceContext ctx = InferenceContext.of(Map.of("x", xSort));
        assertEquals(xSort, NarrowingInference.inferFloor(IrExpr.var("x"), ctx));
    }

    @Test
    void inferFloor_comparison_returnsBoolPin() {
        // A comparison pins to [Bool:@==(x>0)] (infer is open); the floor returns
        // that pin. (The bare-Bool floor is still the fallback when the predicate
        // doesn't compile — exercised via the totality-widening path.)
        IrExpr cmp = IrExpr.binOp(IrExpr.Op.GT, IrExpr.var("x"), IrExpr.lit(0));
        InferenceContext ctx = InferenceContext.of(Map.of("x", IrSort.named("Int")));
        IrSort boolPin = IrSort.refined("Bool", eqSelf(cmp));
        assertEquals(boolPin, NarrowingInference.infer(cmp, ctx));
        assertEquals(boolPin, NarrowingInference.inferFloor(cmp, ctx));
    }

    @Test
    void inferFloor_arith_returnsThePin() {
        // infer is open: unbounded Int arithmetic now pins (x+y → [Int:@==x+y]),
        // so the floor returns that pin (the base fallback is only for the null cases).
        IrExpr sum = IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.var("y"));
        InferenceContext ctx = InferenceContext.of(Map.of(
                "x", IrSort.named("Int"), "y", IrSort.named("Int")));
        assertEquals(intPin(sum), NarrowingInference.infer(sum, ctx));
        assertEquals(intPin(sum), NarrowingInference.inferFloor(sum, ctx));
    }

    @Test
    void inferFloor_inlineStructuralFieldAccess_resolvesViaOwnMembers() {
        // The one floor-only divergence: an inline Structural base (no structDefs
        // entry) resolves via its own members, where infer yields null.
        InferenceContext ctx = InferenceContext.of(Map.of("p", IrSort.structural("Point",
                Map.of("x", IrSort.named("Int"), "y", IrSort.named("Int")))));
        IrExpr fa = IrExpr.fieldAccess(IrExpr.var("p"), "x");
        assertNull(NarrowingInference.infer(fa, ctx));
        assertEquals(IrSort.named("Int"), NarrowingInference.inferFloor(fa, ctx));
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

    /** {@code [Int:@==expr]} — the exact value-pin over an arbitrary expression. */
    private static IrSort intPin(IrExpr expr) {
        return IrSort.refined("Int", eqSelf(expr));
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
