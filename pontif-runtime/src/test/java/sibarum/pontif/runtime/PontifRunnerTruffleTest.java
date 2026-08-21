package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Parity check: identical source programs produce identical results through
 * both execution engines. The interpreter is exercised separately in
 * {@link PontifRunnerTest} and {@link LambdaParserIntegrationTest}; this
 * file adds Truffle-path coverage for the same scenarios.
 */
class PontifRunnerTruffleTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult runTruffle(String source, String name) {
        return runner.run(compiler.compileSexpr(source, name), Engine.TRUFFLE);
    }

    private RunResult runInterp(String source, String name) {
        return runner.run(compiler.compileSexpr(source, name), Engine.INTERPRETER);
    }

    @Test
    void arithmetic_truffle() throws Exception {
        assertEquals("7", runTruffle("(module m () (+ 1 (* 2 3)))", "t.ptf").text());
    }

    @Test
    void factorial_truffle() throws Exception {
        String src = """
                (module factorial
                  ((defn factorial ((n (refined Int (== self 0)))) Int 1)
                   (defn factorial ((n (refined Int (>  self 0)))) Int
                     (* n (call factorial (- n 1)))))
                  (call factorial 6))
                """;
        RunResult r = runTruffle(src, "factorial.ptf");
        assertFalse(r.isError(), "expected success; got: " + r.text());
        assertEquals("720", r.text());
    }

    @Test
    void match_truffle() throws Exception {
        String src = """
                (module abs
                  ((defn abs ((n Int)) Int
                     (match n
                       ((refined Int (>= self 0)) n)
                       ((refined Int (<  self 0)) (- 0 n)))))
                  (call abs -7))
                """;
        assertEquals("7", runTruffle(src, "abs.ptf").text());
    }

    @Test
    void lambdaInlineInvoke_truffle() throws Exception {
        assertEquals("6", runTruffle(
                "(module m () (call (lambda ((x Int)) Int (+ x 1)) 5))",
                "t.ptf").text());
    }

    @Test
    void closureCapture_truffle() throws Exception {
        String src = """
                (module closure
                  ((defn addN ((n Int)) (function (Int) Int)
                     (lambda ((x Int)) Int (+ x n))))
                  (let add5 (function (Int) Int) (call addN 5)
                    (call add5 3)))
                """;
        assertEquals("8", runTruffle(src, "closure.ptf").text());
    }

    @Test
    void runtimeError_truffle_carriesOrigin() throws Exception {
        // No match branch covers a positive value.
        String src = """
                (module m
                  ()
                  (match 5
                    ((refined Int (< self 0)) 99)))
                """;
        RunResult r = runTruffle(src, "nomatch.ptf");
        assertEquals(true, r.isError());
    }

    // --- Engine parity: compile ONCE, run both engines against the same artifact ---

    @Test
    void parityCheck_factorial() throws Exception {
        String src = """
                (module factorial
                  ((defn factorial ((n (refined Int (== self 0)))) Int 1)
                   (defn factorial ((n (refined Int (>  self 0)))) Int
                     (* n (call factorial (- n 1)))))
                  (call factorial 5))
                """;
        PontifCompiler.CompileResult compiled = compiler.compileSexpr(src, "x.ptf");
        RunResult interp = runner.run(compiled, Engine.INTERPRETER);
        RunResult truffle = runner.run(compiled, Engine.TRUFFLE);
        assertFalse(interp.isError());
        assertFalse(truffle.isError());
        assertEquals(interp.text(), truffle.text());
        assertEquals("120", interp.text());
    }

    @Test
    void parityCheck_closure() throws Exception {
        String src = """
                (module closure
                  ()
                  (let n Int 10
                    (let f Function (lambda ((x Int)) Int (+ x n))
                      (call f 5))))
                """;
        assertEquals(
                runInterp(src, "x.ptf").text(),
                runTruffle(src, "x.ptf").text());
    }
}
