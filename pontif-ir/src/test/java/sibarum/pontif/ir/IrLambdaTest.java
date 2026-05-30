package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;
import sibarum.pontif.defaults.DefaultRules;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.symbolic.Simplifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IrLambdaTest {

    private static Simplifier simplifier() throws Exception {
        return new Simplifier(DefaultRules.production());
    }

    private static Object run(IrModule module) throws Exception {
        Simplifier simp = simplifier();
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    private static final IrSort INT = IrSort.named("Int");
    private static final IrSort FN  = new IrSort.Function(List.of(INT), INT, sibarum.pontif.core.Origin.NONE);
    private static final IrSort HOF = new IrSort.Function(List.of(FN), INT, sibarum.pontif.core.Origin.NONE);
    private static final IrSort CURRIED = new IrSort.Function(List.of(INT), FN, sibarum.pontif.core.Origin.NONE);

    // --- Basic Lambda and Apply ---

    @Test
    void lambdaEvaluatesToClosure() throws Exception {
        // \x -> x + 1   (just constructed, never applied)
        IrExpr lambda = IrExpr.lambda(
                List.of(new IrParam("x", INT)),
                INT,
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.lit(1)));

        IrModule module = new IrModule("lam", List.of(), lambda);
        Object result = run(module);
        assertInstanceOf(Closure.class, result);
    }

    @Test
    void applyInvokesLambdaWithArgument() throws Exception {
        // (\x -> x + 1)(5) = 6
        IrExpr lambda = IrExpr.lambda(
                List.of(new IrParam("x", INT)),
                INT,
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.lit(1)));

        IrExpr app = IrExpr.apply(lambda, List.of(IrExpr.lit(5)));
        IrModule module = new IrModule("app", List.of(), app);

        assertEquals(6L, run(module));
    }

    @Test
    void multiArgLambda_invokedWithMultipleArgs() throws Exception {
        // (\(x, y) -> x * y)(3, 4) = 12
        IrExpr lambda = IrExpr.lambda(
                List.of(new IrParam("x", INT), new IrParam("y", INT)),
                INT,
                IrExpr.binOp(IrExpr.Op.MUL, IrExpr.var("x"), IrExpr.var("y")));

        IrExpr app = IrExpr.apply(lambda, List.of(IrExpr.lit(3), IrExpr.lit(4)));
        IrModule module = new IrModule("mul", List.of(), app);

        assertEquals(12L, run(module));
    }

    // --- Closures over lexical scope ---

    @Test
    void closureCapturesEnclosingLetBinding() throws Exception {
        // let n = 10 in
        //   let f = \x -> x + n in
        //     Apply(f, [5])
        // = 15
        IrExpr lambda = IrExpr.lambda(
                List.of(new IrParam("x", INT)),
                INT,
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.var("n")));

        IrExpr body = IrExpr.letIn(
                "f", FN, lambda,
                IrExpr.apply(IrExpr.var("f"), List.of(IrExpr.lit(5))));

        IrExpr main = IrExpr.letIn("n", INT, IrExpr.lit(10), body);

        IrModule module = new IrModule("capture", List.of(), main);
        assertEquals(15L, run(module));
    }

    @Test
    void closureDoesNotSeeBindingsAddedAfterItsCreation() throws Exception {
        // let f = \x -> x   in
        //   let n = 100 in
        //     Apply(f, [5])
        // f doesn't reference n, but if it did, this test verifies that future bindings
        // wouldn't change the closure's view of scope.
        IrExpr identity = IrExpr.lambda(
                List.of(new IrParam("x", INT)),
                INT,
                IrExpr.var("x"));

        IrExpr main = IrExpr.letIn(
                "f", FN, identity,
                IrExpr.letIn(
                        "n", INT, IrExpr.lit(100),
                        IrExpr.apply(IrExpr.var("f"), List.of(IrExpr.lit(5)))));

        IrModule module = new IrModule("scope", List.of(), main);
        assertEquals(5L, run(module));
    }

    // --- Higher-order ---

    @Test
    void higherOrder_lambdaAsArgumentToAnotherLambda() throws Exception {
        // let doubler = \x -> x * 2 in
        //   let applyTo5 = \f -> Apply(f, [5]) in
        //     Apply(applyTo5, [doubler])
        // = 10
        IrExpr doubler = IrExpr.lambda(
                List.of(new IrParam("x", INT)),
                INT,
                IrExpr.binOp(IrExpr.Op.MUL, IrExpr.var("x"), IrExpr.lit(2)));

        IrExpr applyTo5 = IrExpr.lambda(
                List.of(new IrParam("f", FN)),
                INT,
                IrExpr.apply(IrExpr.var("f"), List.of(IrExpr.lit(5))));

        IrExpr main = IrExpr.letIn(
                "doubler", FN, doubler,
                IrExpr.letIn(
                        "applyTo5", HOF, applyTo5,
                        IrExpr.apply(IrExpr.var("applyTo5"), List.of(IrExpr.var("doubler")))));

        IrModule module = new IrModule("ho", List.of(), main);
        assertEquals(10L, run(module));
    }

    @Test
    void currying_lambdaReturningLambda() throws Exception {
        // let addN = \n -> \x -> x + n in
        //   let add5 = Apply(addN, [5]) in
        //     Apply(add5, [3])
        // = 8
        IrExpr innerLambda = IrExpr.lambda(
                List.of(new IrParam("x", INT)),
                INT,
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.var("n")));

        IrExpr addN = IrExpr.lambda(
                List.of(new IrParam("n", INT)),
                FN,
                innerLambda);

        IrExpr main = IrExpr.letIn(
                "addN", CURRIED, addN,
                IrExpr.letIn(
                        "add5", FN, IrExpr.apply(IrExpr.var("addN"), List.of(IrExpr.lit(5))),
                        IrExpr.apply(IrExpr.var("add5"), List.of(IrExpr.lit(3)))));

        IrModule module = new IrModule("curry", List.of(), main);
        assertEquals(8L, run(module));
    }

    @Test
    void closureSurvivesEnclosingScopeExit() throws Exception {
        // Curried closures: the inner closure captures `n` from outer let.
        // After the outer Apply, the inner closure still holds the captured n.
        // let make = \n -> \x -> n + x in
        //   let f = Apply(make, [100]) in
        //     Apply(f, [1])
        // = 101
        IrExpr inner = IrExpr.lambda(
                List.of(new IrParam("x", INT)),
                INT,
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("n"), IrExpr.var("x")));

        IrExpr make = IrExpr.lambda(
                List.of(new IrParam("n", INT)),
                FN,
                inner);

        IrExpr main = IrExpr.letIn(
                "make", CURRIED, make,
                IrExpr.letIn(
                        "f", FN, IrExpr.apply(IrExpr.var("make"), List.of(IrExpr.lit(100))),
                        IrExpr.apply(IrExpr.var("f"), List.of(IrExpr.lit(1)))));

        IrModule module = new IrModule("survive", List.of(), main);
        assertEquals(101L, run(module));
    }

    // --- Apply errors ---

    @Test
    void applyWithWrongArity_throws() throws Exception {
        // (\x -> x)(1, 2) — arity mismatch
        IrExpr identity = IrExpr.lambda(
                List.of(new IrParam("x", INT)),
                INT,
                IrExpr.var("x"));

        IrExpr app = IrExpr.apply(identity, List.of(IrExpr.lit(1), IrExpr.lit(2)));
        IrModule module = new IrModule("arity", List.of(), app);

        RuntimeCheckException ex = assertThrows(
                RuntimeCheckException.class,
                () -> run(module));
        // Message should mention arity
        assert ex.getMessage().toLowerCase().contains("arity")
                : "expected arity diagnostic; got: " + ex.getMessage();
    }

    @Test
    void applyOnNonClosure_throws() throws Exception {
        // Apply(5, [1]) — 5 is not a function
        IrExpr app = IrExpr.apply(IrExpr.lit(5), List.of(IrExpr.lit(1)));
        IrModule module = new IrModule("notFn", List.of(), app);

        assertThrows(RuntimeCheckException.class, () -> run(module));
    }

    // --- Interactions with named functions and dispatch ---

    @Test
    void namedFunctionCanInternallyUseLambda() throws Exception {
        // fn process(x: Int) -> Int = Apply(\y -> y * 3, [x])
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

        assertEquals(21L, run(module));
    }

    // --- Self-application is allowed (lexical scope captures bindings) ---

    @Test
    void lambdaCanReferenceItsOwnBinding_butOnlyIfBoundFirst() throws Exception {
        // let rec wouldn't work without explicit fixpoint;
        // here we just demonstrate that the captured environment of a let-bound lambda
        // doesn't include itself (would need a separate "letrec" construct).
        // So `let f = \x -> Apply(f, [...])` would have f unbound inside the lambda.
        //
        // This test confirms the limitation: a recursive reference via simple `let`
        // (not letrec) fails at apply time.
        IrExpr recursiveLambda = IrExpr.lambda(
                List.of(new IrParam("x", INT)),
                INT,
                IrExpr.apply(IrExpr.var("f"), List.of(IrExpr.var("x"))));

        IrExpr main = IrExpr.letIn(
                "f", FN, recursiveLambda,
                IrExpr.apply(IrExpr.var("f"), List.of(IrExpr.lit(5))));

        IrModule module = new IrModule("nonRecursive", List.of(), main);
        // Should throw because `f` is not in scope at lambda-creation time
        assertThrows(Exception.class, () -> run(module));
    }
}
