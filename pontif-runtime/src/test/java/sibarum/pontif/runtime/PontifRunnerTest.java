package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PontifRunnerTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String source, String name) {
        return runner.run(compiler.compileSexpr(source, name), Engine.INTERPRETER);
    }

    @Test
    void simpleArithmeticProgram_returnsSuccessWithStringResult() throws Exception {
        RunResult r = run("(module m () (+ 1 (* 2 3)))", "test.ptf");
        assertFalse(r.isError(), "expected success; got: " + r.text());
        assertEquals("7", r.text());
        assertTrue(r.origin().isEmpty());
    }

    @Test
    void factorialModule_runsToOneHundredTwenty() throws Exception {
        String src = """
                (module factorial
                  ((defn factorial ((n (refined Int (>= self 0)))) Int
                     (match n
                       ((refined Int (== self 0)) 1)
                       ((refined Int (> self 0)) (* n (call factorial (- n 1)))))))
                  (call factorial 5))
                """;
        RunResult r = run(src, "factorial.ptf");
        assertFalse(r.isError(), "expected success; got: " + r.text());
        assertEquals("120", r.text());
    }

    @Test
    void parseError_returnsErrorWithOrigin() throws Exception {
        RunResult r = run("(module m () (+ 1", "broken.ptf");
        assertTrue(r.isError());
        assertTrue(r.text().toLowerCase().contains("parse"),
                "expected parse-error prefix; got: " + r.text());
        assertTrue(r.origin().isPresent(), "expected origin on parse error");
        assertTrue(r.origin().get().toString().contains("broken.ptf"),
                "expected origin to name source; got: " + r.origin().get());
    }

    @Test
    void runtimeError_returnsErrorWithOrigin() throws Exception {
        // Division by zero — a runtime error with origin. (This test used to
        // provoke a match no-match, but match totality is enforced at compile
        // time now — a partial match without a default no longer compiles, so
        // the runtime no-match path is unreachable through a checked program.)
        RunResult r = runner.run(
                compiler.compile("function f(x:Int):Int -> 10 / x\nf(0)", "divzero.ptf"),
                Engine.INTERPRETER);
        assertTrue(r.isError());
        assertTrue(r.text().toLowerCase().contains("runtime"),
                "expected runtime-error prefix; got: " + r.text());
        assertTrue(r.origin().isPresent(), "expected origin on runtime error");
    }

    @Test
    void sameCompiledProgram_canRunMultipleTimes_withoutReparsing() throws Exception {
        // Demonstrates the separation: compile once, run many times.
        PontifCompiler.CompileResult result = compiler.compileSexpr(
                "(module m () (+ 1 (* 2 3)))", "x.ptf");
        for (int i = 0; i < 3; i++) {
            assertEquals("7", runner.run(result, Engine.INTERPRETER).text());
        }
    }
}
