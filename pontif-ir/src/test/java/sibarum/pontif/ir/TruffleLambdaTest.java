package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;
import sibarum.pontif.defaults.DefaultRules;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.symbolic.Simplifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TruffleLambdaTest {

    private static Simplifier simplifier() throws Exception {
        return new Simplifier(DefaultRules.production());
    }

    private static Object runTruffle(IrModule module) throws Exception {
        Simplifier simp = simplifier();
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        TruffleProgram program = new TruffleLowering(compiler).lower(compiled);
        return program.run();
    }

    private static final IrSort INT = IrSort.named("Int");
    private static final IrSort FN = new IrSort.CallSig(IrSort.CallSig.METHOD, List.of(INT), INT, sibarum.pontif.core.Origin.NONE);
    private static final IrSort HOF = new IrSort.CallSig(IrSort.CallSig.METHOD, List.of(FN), INT, sibarum.pontif.core.Origin.NONE);
    private static final IrSort CURRIED = new IrSort.CallSig(IrSort.CallSig.METHOD, List.of(INT), FN, sibarum.pontif.core.Origin.NONE);

    // --- Basic Apply ---

    @Test
    void truffle_closedLambda_appliedToLiteral() throws Exception {
        // (\x -> x + 1)(5) = 6
        IrExpr lambda = IrExpr.lambda(
                List.of(new IrParam("x", INT)),
                INT,
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.lit(1)));
        IrExpr app = IrExpr.apply(lambda, List.of(IrExpr.lit(5)));
        assertEquals(6L, runTruffle(new IrModule("app", List.of(), app)));
    }

    @Test
    void truffle_multiArgLambda_invokedWithMultipleArgs() throws Exception {
        // (\(x, y) -> x * y)(3, 4) = 12
        IrExpr lambda = IrExpr.lambda(
                List.of(new IrParam("x", INT), new IrParam("y", INT)),
                INT,
                IrExpr.binOp(IrExpr.Op.MUL, IrExpr.var("x"), IrExpr.var("y")));
        IrExpr app = IrExpr.apply(lambda, List.of(IrExpr.lit(3), IrExpr.lit(4)));
        assertEquals(12L, runTruffle(new IrModule("mul", List.of(), app)));
    }

    // --- Closure capture ---

    @Test
    void truffle_closureCapturesEnclosingLetBinding() throws Exception {
        // let n = 10 in let f = \x -> x + n in f(5)  = 15
        IrExpr lambda = IrExpr.lambda(
                List.of(new IrParam("x", INT)),
                INT,
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.var("n")));
        IrExpr body = IrExpr.letIn("f", FN, lambda,
                IrExpr.apply(IrExpr.var("f"), List.of(IrExpr.lit(5))));
        IrExpr main = IrExpr.letIn("n", INT, IrExpr.lit(10), body);
        assertEquals(15L, runTruffle(new IrModule("capture", List.of(), main)));
    }

    @Test
    void truffle_closureDoesNotSeeBindingsAddedAfterCreation() throws Exception {
        // let f = \x -> x in let n = 100 in f(5)  = 5  (n is unused)
        IrExpr identity = IrExpr.lambda(
                List.of(new IrParam("x", INT)),
                INT,
                IrExpr.var("x"));
        IrExpr main = IrExpr.letIn("f", FN, identity,
                IrExpr.letIn("n", INT, IrExpr.lit(100),
                        IrExpr.apply(IrExpr.var("f"), List.of(IrExpr.lit(5)))));
        assertEquals(5L, runTruffle(new IrModule("scope", List.of(), main)));
    }

    // --- Higher-order ---

    @Test
    void truffle_higherOrder_lambdaAsArgumentToAnotherLambda() throws Exception {
        // let doubler = \x -> x * 2 in let applyTo5 = \f -> f(5) in applyTo5(doubler) = 10
        IrExpr doubler = IrExpr.lambda(
                List.of(new IrParam("x", INT)),
                INT,
                IrExpr.binOp(IrExpr.Op.MUL, IrExpr.var("x"), IrExpr.lit(2)));
        IrExpr applyTo5 = IrExpr.lambda(
                List.of(new IrParam("f", FN)),
                INT,
                IrExpr.apply(IrExpr.var("f"), List.of(IrExpr.lit(5))));
        IrExpr main = IrExpr.letIn("doubler", FN, doubler,
                IrExpr.letIn("applyTo5", HOF, applyTo5,
                        IrExpr.apply(IrExpr.var("applyTo5"), List.of(IrExpr.var("doubler")))));
        assertEquals(10L, runTruffle(new IrModule("ho", List.of(), main)));
    }

    @Test
    void truffle_currying_lambdaReturningLambda() throws Exception {
        // let addN = \n -> \x -> x + n in let add5 = addN(5) in add5(3) = 8
        IrExpr inner = IrExpr.lambda(
                List.of(new IrParam("x", INT)),
                INT,
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.var("n")));
        IrExpr addN = IrExpr.lambda(
                List.of(new IrParam("n", INT)),
                FN,
                inner);
        IrExpr main = IrExpr.letIn("addN", CURRIED, addN,
                IrExpr.letIn("add5", FN,
                        IrExpr.apply(IrExpr.var("addN"), List.of(IrExpr.lit(5))),
                        IrExpr.apply(IrExpr.var("add5"), List.of(IrExpr.lit(3)))));
        assertEquals(8L, runTruffle(new IrModule("curry", List.of(), main)));
    }

    @Test
    void truffle_closureSurvivesEnclosingScopeExit() throws Exception {
        // let make = \n -> \x -> n + x in let f = make(100) in f(1)  = 101
        IrExpr inner = IrExpr.lambda(
                List.of(new IrParam("x", INT)),
                INT,
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("n"), IrExpr.var("x")));
        IrExpr make = IrExpr.lambda(
                List.of(new IrParam("n", INT)),
                FN,
                inner);
        IrExpr main = IrExpr.letIn("make", CURRIED, make,
                IrExpr.letIn("f", FN,
                        IrExpr.apply(IrExpr.var("make"), List.of(IrExpr.lit(100))),
                        IrExpr.apply(IrExpr.var("f"), List.of(IrExpr.lit(1)))));
        assertEquals(101L, runTruffle(new IrModule("survive", List.of(), main)));
    }

    // --- Apply errors with origins ---

    @Test
    void truffle_applyWithWrongArity_throwsWithMatchOrigin() throws Exception {
        Origin applySite = Origin.at("test.ptf", 7, 3);
        IrExpr identity = IrExpr.lambda(
                List.of(new IrParam("x", INT)),
                INT,
                IrExpr.var("x"));
        IrExpr app = new IrExpr.Apply(identity, List.of(IrExpr.lit(1), IrExpr.lit(2)), applySite);
        RuntimeCheckException ex = assertThrows(
                RuntimeCheckException.class,
                () -> runTruffle(new IrModule("arity", List.of(), app)));
        assertEquals(applySite, ex.origin());
        assertTrue(ex.getMessage().toLowerCase().contains("arity"),
                "expected arity diagnostic; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("test.ptf:7:3"),
                "expected origin in message; got: " + ex.getMessage());
    }

    @Test
    void truffle_applyOnNonClosure_isCompileErrorWithApplyOrigin() throws Exception {
        // Statically-never-callable Apply is rejected at compile time now,
        // origin preserved (was a runtime RuntimeCheckException).
        Origin applySite = Origin.at("test.ptf", 9, 5);
        IrExpr app = new IrExpr.Apply(IrExpr.lit(5), List.of(IrExpr.lit(1)), applySite);
        CompileException ex = assertThrows(
                CompileException.class,
                () -> runTruffle(new IrModule("notFn", List.of(), app)));
        assertTrue(ex.getMessage().contains("test.ptf:9:5"),
                "expected origin in message; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("not callable"),
                "expected not-callable diagnostic; got: " + ex.getMessage());
    }

    // --- Interactions with named functions ---

    @Test
    void truffle_namedFunctionCanInternallyUseLambda() throws Exception {
        // fn process(x: Int) -> Int = (\y -> y * 3)(x)
        IrStmt.FunctionDecl processFn = IrStmt.functionDecl(
                "process",
                List.of(new IrParam("x", INT)),
                INT,
                IrExpr.apply(
                        IrExpr.lambda(
                                List.of(new IrParam("y", INT)),
                                INT,
                                IrExpr.binOp(IrExpr.Op.MUL, IrExpr.var("y"), IrExpr.lit(3))),
                        List.of(IrExpr.var("x"))));
        IrModule module = new IrModule("internal",
                List.of(processFn),
                IrExpr.call("process", List.of(IrExpr.lit(7))));
        assertEquals(21L, runTruffle(module));
    }

    // --- Interaction with Match ---

    @Test
    void truffle_lambdaBodyContainingMatch_appliedCorrectly() throws Exception {
        // (\x -> match x with | zero -> 100 | positive -> x * 2)(5) = 10
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        IrSort zero = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), IrExpr.lit(0)));
        IrSort negative = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.LT, IrExpr.self(), IrExpr.lit(0)));
        IrExpr lambda = IrExpr.lambda(
                List.of(new IrParam("x", INT)),
                INT,
                IrExpr.match(IrExpr.var("x"), List.of(
                        IrExpr.matchBranch(zero, IrExpr.lit(100)),
                        IrExpr.matchBranch(positive,
                                IrExpr.binOp(IrExpr.Op.MUL, IrExpr.var("x"), IrExpr.lit(2))),
                        // negative arm completes the cover (x is 5 → never taken).
                        IrExpr.matchBranch(negative, IrExpr.lit(0)))));
        IrExpr app = IrExpr.apply(lambda, List.of(IrExpr.lit(5)));
        assertEquals(10L, runTruffle(new IrModule("lamMatch", List.of(), app)));
    }
}
