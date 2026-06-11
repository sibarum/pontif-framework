package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Phase D.3 coverage: {@link NarrowingInference} consults
 * {@link StaticDispatch} when overloads are present, returning the
 * resolved overload's declared return sort. Falls back to
 * {@code ctx.functionReturns()} when dispatch is Unresolved or no
 * overloads are registered.
 */
class NarrowingInferenceDispatchTest {

    private static final IrSort INT = IrSort.named("Int");
    private static final IrSort POSITIVE = IrSort.refined("Int",
            IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
    private static final IrSort NEGATIVE = IrSort.refined("Int",
            IrExpr.binOp(IrExpr.Op.LT, IrExpr.self(), IrExpr.lit(0)));

    private static IrStmt.FunctionDecl decl(String name, IrSort param, IrSort ret) {
        return IrStmt.functionDecl(name, List.of(new IrParam("x", param)), ret, IrExpr.lit(0));
    }

    // --- Dispatched return sort propagates -----------------------------------

    @Test
    void multipleOverloads_argNarrowsToSpecific_returnsSpecificReturn() {
        // sign(x:[Int:@>0]):[Int:@==1]
        // sign(x:[Int:@<0]):[Int:@==-1]
        IrSort retPositive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), IrExpr.lit(1)));
        IrSort retNegative = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), IrExpr.lit(-1)));
        IrStmt.FunctionDecl onPositive = decl("sign", POSITIVE, retPositive);
        IrStmt.FunctionDecl onNegative = decl("sign", NEGATIVE, retNegative);

        // Calling sign(5) — arg narrowing is [Int:@==5], implies @>0.
        InferenceContext ctx = InferenceContext.empty()
                .withOverloads(Map.of("sign", List.of(onPositive, onNegative)));

        IrSort result = NarrowingInference.infer(
                IrExpr.call("sign", List.of(IrExpr.lit(5))), ctx);

        assertEquals(retPositive, result);
    }

    @Test
    void multipleOverloads_argNarrowsToNegative_returnsNegativeOverload() {
        IrSort retPositive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), IrExpr.lit(1)));
        IrSort retNegative = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), IrExpr.lit(-1)));
        IrStmt.FunctionDecl onPositive = decl("sign", POSITIVE, retPositive);
        IrStmt.FunctionDecl onNegative = decl("sign", NEGATIVE, retNegative);

        InferenceContext ctx = InferenceContext.empty()
                .withOverloads(Map.of("sign", List.of(onPositive, onNegative)));

        IrSort result = NarrowingInference.infer(
                IrExpr.call("sign", List.of(IrExpr.lit(-3))), ctx);

        assertEquals(retNegative, result);
    }

    // --- Most-specific resolution flows through inference --------------------

    @Test
    void catchAllPlusSpecialization_picksSpecializationReturn() {
        // handle(x:Int):Int  (catch-all)
        // handle(x:[Int:@>0]):[Int:@>=1]  (specialization with tighter return)
        IrSort tighterReturn = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(1)));
        IrStmt.FunctionDecl catchAll = decl("handle", INT, INT);
        IrStmt.FunctionDecl specialization = decl("handle", POSITIVE, tighterReturn);

        InferenceContext ctx = InferenceContext.empty()
                .withOverloads(Map.of("handle", List.of(catchAll, specialization)));

        IrSort result = NarrowingInference.infer(
                IrExpr.call("handle", List.of(IrExpr.lit(5))), ctx);

        assertEquals(tighterReturn, result);
    }

    // --- Fallback to declared when dispatch is Unresolved --------------------

    @Test
    void unresolvedDispatch_fallsBackToFunctionReturns() {
        // Two disjoint overloads, arg's narrowing is null (unknown) →
        // StaticDispatch returns Unresolved → fall back to declared.
        IrSort retPos = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), IrExpr.lit(1)));
        IrSort retNeg = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), IrExpr.lit(-1)));
        IrStmt.FunctionDecl pos = decl("sign", POSITIVE, retPos);
        IrStmt.FunctionDecl neg = decl("sign", NEGATIVE, retNeg);

        // Call sign(x + 1) — BinOp's narrowing is null in Phase A.
        // Static dispatch can't decide → fall back to functionReturns.get("sign").
        InferenceContext ctx = InferenceContext.empty()
                .withOverloads(Map.of("sign", List.of(pos, neg)));
        // Add a fallback declared return.
        Map<String, IrSort> returns = Map.of("sign", IrSort.named("Int"));
        ctx = new InferenceContext(ctx.typeEnv(), returns, ctx.structDefs(), ctx.overloads(),
                ctx.returnProofs());

        IrSort result = NarrowingInference.infer(
                IrExpr.call("sign",
                        List.of(IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("n"), IrExpr.lit(1)))),
                ctx);

        // Falls back to declared Int.
        assertEquals(IrSort.named("Int"), result);
    }

    @Test
    void noOverloadsRegistered_phaseAFallbackPath() {
        // No overloads registered; functionReturns has the declared return.
        // This is the Phase A behavior preserved.
        IrSort declaredReturn = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(1)));
        InferenceContext ctx = InferenceContext.of(Map.of(), Map.of("factorial", declaredReturn));

        IrSort result = NarrowingInference.infer(
                IrExpr.call("factorial", List.of(IrExpr.lit(3))), ctx);

        assertEquals(declaredReturn, result);
    }

    @Test
    void unknownFunctionAndNoOverloads_returnsNull() {
        IrSort result = NarrowingInference.infer(
                IrExpr.call("notDeclared", List.of()),
                InferenceContext.empty());
        assertNull(result);
    }

    // --- fromModule helper end-to-end ---------------------------------------

    @Test
    void fromModule_populatesOverloadsAndStructDefs() {
        IrSort retPos = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), IrExpr.lit(1)));
        IrSort retNeg = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), IrExpr.lit(-1)));
        IrModule module = new IrModule("m",
                List.of(
                        decl("sign", POSITIVE, retPos),
                        decl("sign", NEGATIVE, retNeg)),
                IrExpr.lit(0));

        InferenceContext ctx = InferenceContext.fromModule(module);

        IrSort result = NarrowingInference.infer(
                IrExpr.call("sign", List.of(IrExpr.lit(7))), ctx);
        assertEquals(retPos, result);
    }

    // --- assign-proof return narrowing (call-site, per region) ---------------

    private static final IrSort NONNEG = IrSort.refined("Int",
            IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(0)));

    private static InferenceContext proveBranchCtx() {
        // proveBranch(d:Int, x:Int):[Int], with two assign-proofs:
        //   d<0  grants [Int:@<0]
        //   d>=0 grants [Int:@>=-16]
        IrSort retNeg = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.LT, IrExpr.self(), IrExpr.lit(0)));
        IrSort retGe16 = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(-16)));
        IrStmt.FunctionDecl fn = IrStmt.functionDecl("proveBranch",
                List.of(new IrParam("d", INT), new IrParam("x", INT)), INT, IrExpr.lit(0));
        IrStmt.ReturnProof pNeg = IrStmt.returnProof("proveBranch",
                List.of(new IrParam("d", NEGATIVE), new IrParam("x", INT)), retNeg, null);
        IrStmt.ReturnProof pPos = IrStmt.returnProof("proveBranch",
                List.of(new IrParam("d", NONNEG), new IrParam("x", INT)), retGe16, null);
        return new InferenceContext(Map.of(),
                Map.of("proveBranch", INT),
                Map.of(),
                Map.of("proveBranch", List.of(fn)),
                Map.of("proveBranch", List.of(pNeg, pPos)));
    }

    @Test
    void returnProof_argInKnownRegion_returnsThatRegionsGrant() {
        // proveBranch(5, 0): arg d=5 lands in the d>=0 region, so the call observes
        // that region's granted [Int:@>=-16] — not the declared base [Int].
        IrSort result = NarrowingInference.infer(
                IrExpr.call("proveBranch", List.of(IrExpr.lit(5), IrExpr.lit(0))),
                proveBranchCtx());
        assertEquals(IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(-16))), result);
    }

    @Test
    void returnProof_argInOtherRegion_returnsOtherGrant() {
        // proveBranch(-3, 0): arg d=-3 lands in the d<0 region → granted [Int:@<0].
        IrSort result = NarrowingInference.infer(
                IrExpr.call("proveBranch", List.of(IrExpr.lit(-3), IrExpr.lit(0))),
                proveBranchCtx());
        assertEquals(IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.LT, IrExpr.self(), IrExpr.lit(0))), result);
    }

    @Test
    void returnProof_argRegionUnknown_fallsBackToDeclaredBase() {
        // proveBranch(n + 1, 0): the first arg's narrowing is unknown, so neither
        // region definitely matches → fall back to the declared base [Int].
        IrSort result = NarrowingInference.infer(
                IrExpr.call("proveBranch", List.of(
                        IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("n"), IrExpr.lit(1)),
                        IrExpr.lit(0))),
                proveBranchCtx());
        assertEquals(INT, result);
    }

    @Test
    void fromModule_collectsReturnProofs_andNarrowsCall() {
        // The end-to-end context builder must collect ReturnProof statements, not
        // just function decls — otherwise consumers (ConstructionGate, Drafter) that
        // build via fromModule never see the region narrowing.
        IrSort retGe16 = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(-16)));
        IrStmt.FunctionDecl fn = IrStmt.functionDecl("proveBranch",
                List.of(new IrParam("d", INT), new IrParam("x", INT)), INT, IrExpr.lit(0));
        IrStmt.ReturnProof pPos = IrStmt.returnProof("proveBranch",
                List.of(new IrParam("d", NONNEG), new IrParam("x", INT)), retGe16, null);
        IrModule module = new IrModule("m", List.of(fn, pPos), IrExpr.lit(0));

        InferenceContext ctx = InferenceContext.fromModule(module);
        IrSort result = NarrowingInference.infer(
                IrExpr.call("proveBranch", List.of(IrExpr.lit(5), IrExpr.lit(0))), ctx);

        assertEquals(retGe16, result);
    }

    // --- Nested call narrowings propagate -----------------------------------

    @Test
    void nestedCalls_innerReturnNarrowingFlowsIntoOuterDispatch() {
        // identityPositive(x:[Int:@>0]):[Int:@>0]
        // classify(x:[Int:@>0]):[Int:@==1]
        // classify(x:[Int:@<=0]):[Int:@==0]
        //
        // Call classify(identityPositive(5))
        //   → identityPositive(5) narrows to [Int:@>0]
        //   → classify([Int:@>0]) resolves to the positive overload
        //   → return narrowing [Int:@==1]
        IrSort nonPositive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.LE, IrExpr.self(), IrExpr.lit(0)));
        IrSort retEq1 = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), IrExpr.lit(1)));
        IrSort retEq0 = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), IrExpr.lit(0)));

        IrStmt.FunctionDecl identity = decl("identityPositive", POSITIVE, POSITIVE);
        IrStmt.FunctionDecl classifyPos = decl("classify", POSITIVE, retEq1);
        IrStmt.FunctionDecl classifyNeg = decl("classify", nonPositive, retEq0);

        InferenceContext ctx = InferenceContext.empty().withOverloads(Map.of(
                "identityPositive", List.of(identity),
                "classify", List.of(classifyPos, classifyNeg)));

        IrExpr nested = IrExpr.call("classify",
                List.of(IrExpr.call("identityPositive", List.of(IrExpr.lit(5)))));

        IrSort result = NarrowingInference.infer(nested, ctx);

        assertNotNull(result);
        assertEquals(retEq1, result);
    }
}
