package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compile → run seam: what a {@link RunResult} carries, and that compiling is separate
 * from running. Ported from the S-expression syntax when that parser was decommissioned.
 */
class PontifRunnerTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String source, String name) {
        return runner.run(compiler.compile(source, name), Engine.INTERPRETER);
    }

    @Test
    void simpleArithmeticProgram_returnsSuccessWithStringResult() {
        RunResult r = run("1 + 2 * 3", "test.ptf");
        assertFalse(r.isError(), () -> "expected success; got: " + r.text());
        assertEquals("7", r.text());
        assertTrue(r.origin().isEmpty());
    }

    @Test
    void factorialModule_runsToOneHundredTwenty() {
        RunResult r = run("""
                function factorial(n:[Int:@>=0]):Int -> match n
                  [@==0] -> 1
                  [@>0]  -> n * factorial(n - 1)
                factorial(5)
                """, "factorial.ptf");
        assertFalse(r.isError(), () -> "expected success; got: " + r.text());
        assertEquals("120", r.text());
    }

    @Test
    void parseError_returnsErrorWithOrigin() {
        RunResult r = run("function f(x:Int):Int ->", "broken.ptf");
        assertTrue(r.isError());
        assertTrue(r.text().toLowerCase().contains("parse"),
                () -> "expected parse-error prefix; got: " + r.text());
        assertTrue(r.origin().isPresent(), "expected origin on parse error");
        assertTrue(r.origin().get().toString().contains("broken.ptf"),
                () -> "expected origin to name source; got: " + r.origin().get());
    }

    @Test
    void runtimeError_returnsErrorWithOrigin() {
        // Division by zero — a runtime error with origin. (This used to provoke a match
        // no-match, but match totality is enforced at compile time now, so the runtime
        // no-match path is unreachable through a checked program.)
        RunResult r = run("function f(x:Int):Int -> 10 / x\nf(0)", "divzero.ptf");
        assertTrue(r.isError());
        assertTrue(r.text().toLowerCase().contains("runtime"),
                () -> "expected runtime-error prefix; got: " + r.text());
        assertTrue(r.origin().isPresent(), "expected origin on runtime error");
    }

    @Test
    void sameCompiledProgram_canRunMultipleTimes_withoutReparsing() {
        // The separation: compile once, run many times.
        PontifCompiler.CompileResult result = compiler.compile("1 + 2 * 3", "x.ptf");
        for (int i = 0; i < 3; i++) {
            assertEquals("7", runner.run(result, Engine.INTERPRETER).text());
        }
    }
}
