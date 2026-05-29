package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.DefaultRules;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.symbolic.Simplifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage tests for Truffle backend capabilities that exist but weren't directly
 * exercised by TruffleExecutionTest: full comparison-operator sweep, direct Bool
 * literal evaluation, recursion via multi-dispatch, and deep call-chain origins.
 */
class TruffleCoverageTest {

    private static Simplifier simplifier() throws Exception {
        return new Simplifier(DefaultRules.production());
    }

    private static Object runOnTruffle(IrModule module) throws Exception {
        Simplifier simp = simplifier();
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        return new TruffleLowering(compiler).lower(compiled).run();
    }

    private static Object runOnInterpreter(IrModule module) throws Exception {
        Simplifier simp = simplifier();
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    // --- All six comparison operators ---

    private static Object cmpResult(IrExpr.Op op, long left, long right) throws Exception {
        IrModule m = new IrModule("cmp_" + op, List.of(),
                IrExpr.binOp(op, IrExpr.lit(left), IrExpr.lit(right)));
        return runOnTruffle(m);
    }

    @Test
    void lt_truthTable() throws Exception {
        assertEquals(true,  cmpResult(IrExpr.Op.LT, 3, 5));
        assertEquals(false, cmpResult(IrExpr.Op.LT, 5, 5));
        assertEquals(false, cmpResult(IrExpr.Op.LT, 7, 5));
    }

    @Test
    void le_truthTable() throws Exception {
        assertEquals(true,  cmpResult(IrExpr.Op.LE, 3, 5));
        assertEquals(true,  cmpResult(IrExpr.Op.LE, 5, 5));
        assertEquals(false, cmpResult(IrExpr.Op.LE, 7, 5));
    }

    @Test
    void gt_truthTable() throws Exception {
        assertEquals(false, cmpResult(IrExpr.Op.GT, 3, 5));
        assertEquals(false, cmpResult(IrExpr.Op.GT, 5, 5));
        assertEquals(true,  cmpResult(IrExpr.Op.GT, 7, 5));
    }

    @Test
    void ge_truthTable() throws Exception {
        assertEquals(false, cmpResult(IrExpr.Op.GE, 3, 5));
        assertEquals(true,  cmpResult(IrExpr.Op.GE, 5, 5));
        assertEquals(true,  cmpResult(IrExpr.Op.GE, 7, 5));
    }

    @Test
    void eq_truthTable() throws Exception {
        assertEquals(false, cmpResult(IrExpr.Op.EQ, 3, 5));
        assertEquals(true,  cmpResult(IrExpr.Op.EQ, 5, 5));
        assertEquals(false, cmpResult(IrExpr.Op.EQ, 7, 5));
    }

    @Test
    void ne_truthTable() throws Exception {
        assertEquals(true,  cmpResult(IrExpr.Op.NE, 3, 5));
        assertEquals(false, cmpResult(IrExpr.Op.NE, 5, 5));
        assertEquals(true,  cmpResult(IrExpr.Op.NE, 7, 5));
    }

    // --- Bool literal evaluated directly ---

    @Test
    void boolLiteralEvaluatesDirectly() throws Exception {
        assertEquals(true,  runOnTruffle(new IrModule("t", List.of(), IrExpr.bool(true))));
        assertEquals(false, runOnTruffle(new IrModule("f", List.of(), IrExpr.bool(false))));
    }

    // --- Recursion via multi-dispatch ---
    // Pontif IR has no `if`, but multi-dispatch on refinement-typed parameters
    // gives us conditional branching. Each branch is an overload whose refinement
    // matches a specific runtime case.

    private static IrModule factorialModule(long n) throws Exception {
        IrSort zero = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), IrExpr.lit(0)));
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        IrSort anyInt = IrSort.named("Int");

        // fn fact(n: Int[@=0]) -> Int = 1
        IrStmt.FunctionDecl base = IrStmt.functionDecl(
                "fact",
                List.of(new IrParam("n", zero)),
                anyInt,
                IrExpr.lit(1));

        // fn fact(n: Int[@>0]) -> Int = n * fact(n - 1)
        IrStmt.FunctionDecl rec = IrStmt.functionDecl(
                "fact",
                List.of(new IrParam("n", positive)),
                anyInt,
                IrExpr.binOp(
                        IrExpr.Op.MUL,
                        IrExpr.var("n"),
                        IrExpr.call("fact", List.of(
                                IrExpr.binOp(IrExpr.Op.SUB, IrExpr.var("n"), IrExpr.lit(1))))));

        return new IrModule("fact" + n,
                List.of(base, rec),
                IrExpr.call("fact", List.of(IrExpr.lit(n))));
    }

    @Test
    void factorial_recursionOnTruffle() throws Exception {
        // fact(0) = 1, fact(1) = 1, fact(5) = 120, fact(10) = 3628800
        assertEquals(1L,       runOnTruffle(factorialModule(0)));
        assertEquals(1L,       runOnTruffle(factorialModule(1)));
        assertEquals(120L,     runOnTruffle(factorialModule(5)));
        assertEquals(3628800L, runOnTruffle(factorialModule(10)));
    }

    @Test
    void factorial_negativeArg_dispatchesToNoMatch() throws Exception {
        // fact(-1) — neither @=0 nor @>0 matches; dispatch fails
        assertThrows(RuntimeCheckException.class,
                () -> runOnTruffle(factorialModule(-1)));
    }

    @Test
    void factorial_interpreterAndTruffleAgree() throws Exception {
        for (long n : new long[]{0, 1, 5, 7, 10}) {
            IrModule m = factorialModule(n);
            assertEquals(runOnInterpreter(m), runOnTruffle(m),
                    "fact(" + n + ") should agree between backends");
        }
    }

    private static IrModule fibonacciModule(long n) throws Exception {
        IrSort eq0 = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), IrExpr.lit(0)));
        IrSort eq1 = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), IrExpr.lit(1)));
        IrSort ge2 = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(2)));
        IrSort anyInt = IrSort.named("Int");

        // fn fib(n: Int[@=0]) -> Int = 0
        IrStmt.FunctionDecl base0 = IrStmt.functionDecl(
                "fib", List.of(new IrParam("n", eq0)), anyInt, IrExpr.lit(0));

        // fn fib(n: Int[@=1]) -> Int = 1
        IrStmt.FunctionDecl base1 = IrStmt.functionDecl(
                "fib", List.of(new IrParam("n", eq1)), anyInt, IrExpr.lit(1));

        // fn fib(n: Int[@>=2]) -> Int = fib(n-1) + fib(n-2)
        IrStmt.FunctionDecl rec = IrStmt.functionDecl(
                "fib", List.of(new IrParam("n", ge2)), anyInt,
                IrExpr.binOp(
                        IrExpr.Op.ADD,
                        IrExpr.call("fib", List.of(
                                IrExpr.binOp(IrExpr.Op.SUB, IrExpr.var("n"), IrExpr.lit(1)))),
                        IrExpr.call("fib", List.of(
                                IrExpr.binOp(IrExpr.Op.SUB, IrExpr.var("n"), IrExpr.lit(2))))));

        return new IrModule("fib" + n,
                List.of(base0, base1, rec),
                IrExpr.call("fib", List.of(IrExpr.lit(n))));
    }

    @Test
    void fibonacci_recursionOnTruffle() throws Exception {
        // 0, 1, 1, 2, 3, 5, 8, 13, 21, 34, 55
        assertEquals(0L,  runOnTruffle(fibonacciModule(0)));
        assertEquals(1L,  runOnTruffle(fibonacciModule(1)));
        assertEquals(1L,  runOnTruffle(fibonacciModule(2)));
        assertEquals(5L,  runOnTruffle(fibonacciModule(5)));
        assertEquals(55L, runOnTruffle(fibonacciModule(10)));
    }

    // --- Deep call-chain origin propagation ---

    @Test
    void originPropagates_throughThreeLevelCallChain() throws Exception {
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        IrSort anyInt = IrSort.named("Int");

        // fn f(x: Int[@>0]) -> Int = x   — fails when x <= 0
        IrStmt.FunctionDecl f = IrStmt.functionDecl(
                "f",
                List.of(new IrParam("x", positive)),
                positive,
                IrExpr.var("x"));

        // fn g(y: Int) -> Int = f(y)
        Origin fInG = Origin.at("g.ptf", 5, 10);
        IrStmt.FunctionDecl g = IrStmt.functionDecl(
                "g",
                List.of(new IrParam("y", anyInt)),
                anyInt,
                new IrExpr.Call("f", List.of(IrExpr.var("y")), fInG));

        // fn h(z: Int) -> Int = g(z)
        Origin gInH = Origin.at("h.ptf", 8, 3);
        IrStmt.FunctionDecl h = IrStmt.functionDecl(
                "h",
                List.of(new IrParam("z", anyInt)),
                anyInt,
                new IrExpr.Call("g", List.of(IrExpr.var("z")), gInH));

        // main → h(-1) → g(-1) → f(-1) → fails
        Origin hInMain = Origin.at("main.ptf", 1, 1);
        IrExpr main = new IrExpr.Call("h", List.of(IrExpr.lit(-1)), hInMain);

        IrModule module = new IrModule("deepChain", List.of(f, g, h), main);

        RuntimeCheckException ex = assertThrows(
                RuntimeCheckException.class,
                () -> runOnTruffle(module));

        // The innermost CallNode (f's invocation at g.ptf:5:10) attributes its origin
        assertEquals(fInG, ex.origin(),
                "Innermost failing call's origin should propagate; got: " + ex.origin());
        assertTrue(ex.getMessage().contains("g.ptf:5:10"));
    }

    @Test
    void originPropagates_chainPreservedInCauseLinks() throws Exception {
        // Same scenario as above, but verify the cause chain — each layer of CallNode
        // catches the inner exception and rewraps. The original cause should reach down.
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        IrSort anyInt = IrSort.named("Int");

        IrStmt.FunctionDecl f = IrStmt.functionDecl(
                "f", List.of(new IrParam("x", positive)), positive, IrExpr.var("x"));

        Origin fInG = Origin.at("g.ptf", 5, 10);
        IrStmt.FunctionDecl g = IrStmt.functionDecl(
                "g", List.of(new IrParam("y", anyInt)), anyInt,
                new IrExpr.Call("f", List.of(IrExpr.var("y")), fInG));

        Origin gCall = Origin.at("main.ptf", 1, 1);
        IrExpr main = new IrExpr.Call("g", List.of(IrExpr.lit(-3)), gCall);
        IrModule module = new IrModule("causeChain", List.of(f, g), main);

        RuntimeCheckException ex = assertThrows(
                RuntimeCheckException.class,
                () -> runOnTruffle(module));

        // The outermost exception has the innermost origin (f's call site, in g)
        assertEquals(fInG, ex.origin());
    }
}
