package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.symbolic.Simplifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TruffleExecutionTest {

    private static List<RewriteRule> defaultRules() {
        List<RewriteRule> rules = new ArrayList<>();
        rules.add(cmpLitLit());
        return rules;
    }

    private static RewriteRule cmpLitLit() {
        return (expr, simp) -> {
            if (expr instanceof sibarum.pontif.core.symbolic.SymExpr.Cmp(
                    sibarum.pontif.core.symbolic.SymExpr.Lit l,
                    sibarum.pontif.core.symbolic.SymExpr.CmpOp op,
                    sibarum.pontif.core.symbolic.SymExpr.Lit r)) {
                boolean truth = switch (op) {
                    case LT -> l.value() < r.value();
                    case LE -> l.value() <= r.value();
                    case GT -> l.value() > r.value();
                    case GE -> l.value() >= r.value();
                    case EQ -> l.value() == r.value();
                    case NE -> l.value() != r.value();
                };
                return Optional.of(sibarum.pontif.core.symbolic.SymExpr.bool(truth));
            }
            return Optional.empty();
        };
    }

    private static Simplifier simplifier() throws Exception {
        return new Simplifier(defaultRules());
    }

    private static Object runOnTruffle(IrModule module) throws Exception {
        Simplifier simp = simplifier();
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        TruffleLowering lowering = new TruffleLowering(compiler);
        TruffleProgram program = lowering.lower(compiled);
        return program.run();
    }

    // --- Simple value tests ---

    @Test
    void evaluatesLiteralOnTruffle() throws Exception {
        IrModule module = new IrModule("lit", List.of(), IrExpr.lit(42));
        assertEquals(42L, runOnTruffle(module));
    }

    @Test
    void evaluatesArithmeticOnTruffle() throws Exception {
        IrModule module = new IrModule("arith", List.of(),
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.lit(20), IrExpr.lit(22)));
        assertEquals(42L, runOnTruffle(module));
    }

    @Test
    void evaluatesMul() throws Exception {
        IrModule module = new IrModule("mul", List.of(),
                IrExpr.binOp(IrExpr.Op.MUL, IrExpr.lit(6), IrExpr.lit(7)));
        assertEquals(42L, runOnTruffle(module));
    }

    @Test
    void evaluatesSub() throws Exception {
        IrModule module = new IrModule("sub", List.of(),
                IrExpr.binOp(IrExpr.Op.SUB, IrExpr.lit(50), IrExpr.lit(8)));
        assertEquals(42L, runOnTruffle(module));
    }

    @Test
    void evaluatesLetIn() throws Exception {
        IrExpr expr = IrExpr.letIn(
                "x", IrSort.named("Int"), IrExpr.lit(7),
                IrExpr.binOp(IrExpr.Op.MUL, IrExpr.var("x"), IrExpr.var("x")));
        IrModule module = new IrModule("letdemo", List.of(), expr);
        assertEquals(49L, runOnTruffle(module));
    }

    @Test
    void evaluatesComparison() throws Exception {
        IrModule module = new IrModule("cmp", List.of(),
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.lit(5), IrExpr.lit(3)));
        assertEquals(true, runOnTruffle(module));
    }

    // --- Function call tests ---

    @Test
    void simpleFunctionCallOnTruffle() throws Exception {
        // fn double(x: Int) -> Int = x + x
        IrStmt.FunctionDecl doubleDecl = IrStmt.functionDecl(
                "double",
                List.of(new IrParam("x", IrSort.named("Int"))),
                IrSort.named("Int"),
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.var("x")));

        IrModule module = new IrModule("doubler",
                List.of(doubleDecl),
                IrExpr.call("double", List.of(IrExpr.lit(21))));

        assertEquals(42L, runOnTruffle(module));
    }

    // --- Headline: refinement-typed function on Truffle ---

    @Test
    void powWithRefinementTypes_passesOnTruffle() throws Exception {
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        IrSort atLeastOne = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(1)));

        IrStmt.FunctionDecl powDecl = IrStmt.functionDecl(
                "pow",
                List.of(
                        new IrParam("b", positive),
                        new IrParam("x", atLeastOne)),
                positive,
                IrExpr.var("b"));

        IrModule module = new IrModule("powDemo",
                List.of(powDecl),
                IrExpr.call("pow", List.of(IrExpr.lit(5), IrExpr.lit(2))));

        assertEquals(5L, runOnTruffle(module));
    }

    @Test
    void powCalledWithViolatingArg_throwsOnTruffle() throws Exception {
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));

        IrStmt.FunctionDecl powDecl = IrStmt.functionDecl(
                "pow",
                List.of(new IrParam("b", positive)),
                positive,
                IrExpr.var("b"));

        IrModule module = new IrModule("powBad",
                List.of(powDecl),
                IrExpr.call("pow", List.of(IrExpr.lit(-3))));

        assertThrows(RuntimeCheckException.class, () -> runOnTruffle(module));
    }

    // --- Multi-dispatch ---

    @Test
    void multiDispatch_specificWinsOnTruffle() throws Exception {
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        IrSort anyInt = IrSort.named("Int");

        IrStmt.FunctionDecl tight = IrStmt.functionDecl(
                "handle",
                List.of(new IrParam("x", positive)),
                anyInt,
                IrExpr.binOp(IrExpr.Op.MUL, IrExpr.var("x"), IrExpr.lit(100)));

        IrStmt.FunctionDecl loose = IrStmt.functionDecl(
                "handle",
                List.of(new IrParam("x", anyInt)),
                anyInt,
                IrExpr.var("x"));

        IrModule module = new IrModule("dispatch",
                List.of(tight, loose),
                IrExpr.call("handle", List.of(IrExpr.lit(5))));

        assertEquals(500L, runOnTruffle(module));
    }

    @Test
    void multiDispatch_falsthroughToGeneralOnTruffle() throws Exception {
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        IrSort anyInt = IrSort.named("Int");

        IrStmt.FunctionDecl tight = IrStmt.functionDecl(
                "handle",
                List.of(new IrParam("x", positive)),
                anyInt,
                IrExpr.binOp(IrExpr.Op.MUL, IrExpr.var("x"), IrExpr.lit(100)));

        IrStmt.FunctionDecl loose = IrStmt.functionDecl(
                "handle",
                List.of(new IrParam("x", anyInt)),
                anyInt,
                IrExpr.var("x"));

        IrModule module = new IrModule("dispatch2",
                List.of(tight, loose),
                IrExpr.call("handle", List.of(IrExpr.lit(-7))));

        assertEquals(-7L, runOnTruffle(module));
    }

    // --- Composition ---

    @Test
    void nestedFunctionCallsOnTruffle() throws Exception {
        IrSort anyInt = IrSort.named("Int");

        IrStmt.FunctionDecl inc = IrStmt.functionDecl(
                "inc",
                List.of(new IrParam("n", anyInt)),
                anyInt,
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("n"), IrExpr.lit(1)));

        IrStmt.FunctionDecl dbl = IrStmt.functionDecl(
                "double",
                List.of(new IrParam("n", anyInt)),
                anyInt,
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("n"), IrExpr.var("n")));

        IrModule module = new IrModule("compose",
                List.of(inc, dbl),
                IrExpr.call("double", List.of(IrExpr.call("inc", List.of(IrExpr.lit(5))))));

        assertEquals(12L, runOnTruffle(module));
    }

    @Test
    void letInBindsAndCallSeesBindingOnTruffle() throws Exception {
        IrSort anyInt = IrSort.named("Int");

        IrStmt.FunctionDecl addOne = IrStmt.functionDecl(
                "addOne",
                List.of(new IrParam("n", anyInt)),
                anyInt,
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("n"), IrExpr.lit(1)));

        IrExpr main = IrExpr.letIn(
                "x", anyInt, IrExpr.lit(10),
                IrExpr.call("addOne", List.of(IrExpr.var("x"))));

        IrModule module = new IrModule("letCall", List.of(addOne), main);
        assertEquals(11L, runOnTruffle(module));
    }

    // --- Truffle/IR consistency ---

    // --- Origin propagation through Truffle ---

    @Test
    void truffleSide_runtimeCheckFailureCarriesOriginFromCallSite() throws Exception {
        // Build a call with explicit origin
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        IrStmt.FunctionDecl powDecl = IrStmt.functionDecl(
                "pow",
                List.of(new IrParam("b", positive)),
                positive,
                IrExpr.var("b"));

        Origin callSite = Origin.at("truffle.ptf", 12, 4);
        IrExpr badCall = new IrExpr.Call("pow", List.of(IrExpr.lit(-5)), callSite);
        IrModule module = new IrModule("originThroughTruffle", List.of(powDecl), badCall);

        RuntimeCheckException ex = assertThrows(
                RuntimeCheckException.class,
                () -> runOnTruffle(module));

        // The exception caught at the Truffle layer should carry the call site's Origin
        assertEquals(callSite, ex.origin());
        assertTrue(ex.getMessage().contains("truffle.ptf:12:4"),
                "Truffle-thrown exception should include origin prefix; got: " + ex.getMessage());
    }

    @Test
    void truffleSide_dispatchFailureCarriesOrigin() {
        // Call to an undeclared function is now a compile-time error caught
        // by SortChecker before lowering reaches Truffle. The call site's
        // origin is preserved on the CompileException.
        Origin callSite = Origin.at("test.ptf", 7, 2);
        IrExpr badCall = new IrExpr.Call("doesNotExist", List.of(IrExpr.lit(1)), callSite);
        IrModule module = new IrModule("missingTruffle", List.of(), badCall);

        CompileException ex = assertThrows(
                CompileException.class,
                () -> runOnTruffle(module));

        assertEquals(callSite, ex.origin());
        assertTrue(ex.getMessage().contains("test.ptf:7:2"));
    }

    @Test
    void truffleSide_pontifNodeCarriesOriginAfterLowering() throws Exception {
        // Lower a single literal with an origin; verify the PontifNode has it set.
        Origin litOrigin = Origin.at("lit.ptf", 1, 1);
        IrExpr.Lit litWithOrigin = new IrExpr.Lit(42, litOrigin);
        IrModule module = new IrModule("litdemo", List.of(), litWithOrigin);

        Simplifier simp = simplifier();
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        TruffleLowering lowering = new TruffleLowering(compiler);
        TruffleProgram program = lowering.lower(compiled);

        // Smoke test that the program runs; the Origin should also be queryable via
        // the underlying RootNode tree, but accessing that is Truffle-internal — for
        // now we just verify the lowering doesn't break and the run succeeds.
        assertEquals(42L, program.run());
    }

    @Test
    void truffleSide_innerCallExceptionGetsOuterCallOrigin() throws Exception {
        // f(x) calls g(x), g has a precondition violation. f's call site has an origin;
        // g's call site (inside f's body) has a different origin. The exception bubbles up.
        // Whichever Truffle CallNode catches it first attributes the origin.
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        IrSort anyInt = IrSort.named("Int");

        // fn g(x: Int[@>0]) -> Int = x
        IrStmt.FunctionDecl g = IrStmt.functionDecl(
                "g",
                List.of(new IrParam("x", positive)),
                positive,
                IrExpr.var("x"));

        // fn f(y: Int) -> Int = g(y)   — but g's call site has an origin
        Origin gCallSite = Origin.at("inner.ptf", 5, 10);
        IrExpr fBody = new IrExpr.Call("g", List.of(IrExpr.var("y")), gCallSite);
        IrStmt.FunctionDecl f = IrStmt.functionDecl(
                "f",
                List.of(new IrParam("y", anyInt)),
                anyInt,
                fBody);

        // main calls f(-3) — which calls g(-3) — which fails
        Origin fCallSite = Origin.at("main.ptf", 1, 1);
        IrExpr fCall = new IrExpr.Call("f", List.of(IrExpr.lit(-3)), fCallSite);
        IrModule module = new IrModule("nested", List.of(g, f), fCall);

        RuntimeCheckException ex = assertThrows(
                RuntimeCheckException.class,
                () -> runOnTruffle(module));

        // The innermost CallNode (g's) attributes its origin to the failure.
        // The cause chain preserves it.
        assertEquals(gCallSite, ex.origin(),
                "Innermost failing call's origin should be on the exception; got: " + ex.origin());
        assertTrue(ex.getMessage().contains("inner.ptf:5:10"));
    }

    @Test
    void interpreterAndTruffleAgreeOnHeadline() throws Exception {
        // Same program, two backends, same answer.
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));

        IrStmt.FunctionDecl decl = IrStmt.functionDecl(
                "id",
                List.of(new IrParam("b", positive)),
                positive,
                IrExpr.var("b"));

        IrModule module = new IrModule("consistency",
                List.of(decl),
                IrExpr.call("id", List.of(IrExpr.lit(17))));

        Simplifier simp = simplifier();
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);

        Object interpreterResult = new IrInterpreter(simp).eval(compiled);
        Object truffleResult = new TruffleLowering(compiler).lower(compiled).run();

        assertEquals(interpreterResult, truffleResult,
                "Interpreter and Truffle backends should produce the same result");
        assertEquals(17L, truffleResult);
    }
}
