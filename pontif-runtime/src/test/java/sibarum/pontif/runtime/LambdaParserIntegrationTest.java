package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * End-to-end coverage for the unified {@code call} form with lambdas:
 * source string → Parser → IrCompiler → IrInterpreter, via PontifRunner.
 *
 * <p>The same {@code call} keyword handles both named-function invocation
 * and value-level closure invocation; the runtime picks based on whether the
 * name is locally bound.
 */
class LambdaParserIntegrationTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String source, String name) {
        return runner.run(compiler.compile(source, name), PontifRunner.Engine.INTERPRETER);
    }

    @Test
    void inlineLambdaInvokedViaCall_yieldsExpectedValue() throws Exception {
        // (\x -> x + 1)(5) = 6  — compound head, parses as IrExpr.Apply
        RunResult r = run(
                "(module m () (call (lambda ((x Int)) Int (+ x 1)) 5))",
                "inline.ptf");
        assertFalse(r.isError(), "expected success; got: " + r.text());
        assertEquals("6", r.text());
    }

    @Test
    void letBoundLambdaInvokedViaCall_resolvesViaLocalScope() throws Exception {
        // let f = (x -> x*x) in (call f 7) = 49
        // 'f' is a bare symbol → IrExpr.Call; runtime finds it in scope and
        // invokes the closure (skipping the dispatch table).
        String src = """
                (module letLambda
                  ()
                  (let f Function (lambda ((x Int)) Int (* x x))
                    (call f 7)))
                """;
        RunResult r = run(src, "letLambda.ptf");
        assertFalse(r.isError(), "expected success; got: " + r.text());
        assertEquals("49", r.text());
    }

    @Test
    void closureCapturesEnclosingLetBinding() throws Exception {
        // let n = 10 in let f = (x -> x + n) in (call f 5) = 15
        String src = """
                (module closure
                  ()
                  (let n Int 10
                    (let f Function (lambda ((x Int)) Int (+ x n))
                      (call f 5))))
                """;
        RunResult r = run(src, "closure.ptf");
        assertFalse(r.isError(), "expected success; got: " + r.text());
        assertEquals("15", r.text());
    }

    @Test
    void higherOrder_namedFunctionReturnsLambda() throws Exception {
        // defn addN(n) = (x -> x + n)
        // let add5 = (call addN 5) in (call add5 3) = 8
        //
        // First (call addN 5) resolves via the dispatch table (addN not in env).
        // Then (call add5 3) resolves via the local binding for add5.
        String src = """
                (module addN
                  ((defn addN ((n Int)) Function
                     (lambda ((x Int)) Int (+ x n))))
                  (let add5 Function (call addN 5)
                    (call add5 3)))
                """;
        RunResult r = run(src, "addN.ptf");
        assertFalse(r.isError(), "expected success; got: " + r.text());
        assertEquals("8", r.text());
    }

    @Test
    void multiArgLambdaInvokedWithSeveralArgs() throws Exception {
        // ((x y) -> x * y)(3, 4) = 12
        RunResult r = run(
                "(module m () (call (lambda ((x Int) (y Int)) Int (* x y)) 3 4))",
                "multiArg.ptf");
        assertFalse(r.isError(), "expected success; got: " + r.text());
        assertEquals("12", r.text());
    }

    @Test
    void localBindingShadowsDispatchTable() throws Exception {
        // A let-bound 'factorial' shadows the global decl. The let value isn't
        // a closure → runtime error from the closure-call path.
        String src = """
                (module shadow
                  ((defn factorial ((n Int)) Int 999))
                  (let factorial Int 5
                    (call factorial 3)))
                """;
        RunResult r = run(src, "shadow.ptf");
        assertEquals(true, r.isError(),
                "expected error: a Long value can't be invoked as a closure");
    }

    @Test
    void functionSortSyntax_replacesNamedFunctionPlaceholder() throws Exception {
        // Same as higherOrder_namedFunctionReturnsLambda but using the real
        // function sort form instead of a Named "Function" placeholder.
        String src = """
                (module addN
                  ((defn addN ((n Int)) (function (Int) Int)
                     (lambda ((x Int)) Int (+ x n))))
                  (let add5 (function (Int) Int) (call addN 5)
                    (call add5 3)))
                """;
        RunResult r = run(src, "fnSort.ptf");
        assertFalse(r.isError(), "expected success; got: " + r.text());
        assertEquals("8", r.text());
    }

    @Test
    void callOnLiteralAsCompoundHead_isARuntimeError() throws Exception {
        // Applying a literal as a function should surface as a runtime error
        // (the call form parses fine — the failure is at eval time).
        RunResult r = run("(module m () (call (+ 2 3) 1))", "bad.ptf");
        assertEquals(true, r.isError());
    }
}
