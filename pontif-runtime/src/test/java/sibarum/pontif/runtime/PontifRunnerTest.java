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
        return runner.run(compiler.compile(source, name), Engine.INTERPRETER);
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
        // No matching match branch — runtime error with origin.
        String src = """
                (module m
                  ()
                  (match -3
                    ((refined Int (> self 0)) 99)))
                """;
        RunResult r = run(src, "nomatch.ptf");
        assertTrue(r.isError());
        assertTrue(r.text().toLowerCase().contains("runtime"),
                "expected runtime-error prefix; got: " + r.text());
        assertTrue(r.origin().isPresent(), "expected origin on runtime error");
    }

    @Test
    void sameCompiledProgram_canRunMultipleTimes_withoutReparsing() throws Exception {
        // Demonstrates the separation: compile once, run many times.
        PontifCompiler.CompileResult result = compiler.compile(
                "(module m () (+ 1 (* 2 3)))", "x.ptf");
        for (int i = 0; i < 3; i++) {
            assertEquals("7", runner.run(result, Engine.INTERPRETER).text());
        }
    }
}
