package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S9 — the {@code ^} power operator. {@code Int ^ Int} is repeated
 * multiplication (exponent ≥ 0); a Decimal operand promotes the result;
 * {@code ^} binds tighter than {@code *}. Negative Int exponents and
 * non-integer (transcendental) Decimal exponents are out of scope (runtime
 * errors).
 */
class PowerOperatorTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String src, Engine engine) {
        return runner.run(compiler.compileAlt(src, "t.ptf"), engine);
    }

    @Test
    void intPower() {
        for (Engine e : Engine.values()) {
            assertEquals("8", run("2 ^ 3", e).text(), e.toString());
            assertEquals("1", run("5 ^ 0", e).text(), e.toString());
            assertEquals("81", run("3 ^ 4", e).text(), e.toString());
        }
    }

    @Test
    void powerBindsTighterThanMultiply() {
        // 2 * 3 ^ 2 = 2 * (3^2) = 18, not (2*3)^2 = 36.
        for (Engine e : Engine.values()) {
            assertEquals("18", run("2 * 3 ^ 2", e).text(), e.toString());
        }
    }

    @Test
    void decimalPower() {
        // A Decimal base promotes the result: 2.5 ^ 2 = 6.25.
        for (Engine e : Engine.values()) {
            assertEquals("6.25", run("2.5 ^ 2", e).text(), e.toString());
        }
    }

    @Test
    void negativeIntExponent_isError() {
        RunResult r = run("2 ^ -1", Engine.INTERPRETER);
        assertTrue(r.isError(), "negative Int exponent isn't an integer — should error");
    }

    @Test
    void nonIntegerDecimalExponent_isError() {
        RunResult r = run("4.0 ^ 0.5", Engine.INTERPRETER);
        assertTrue(r.isError(), "a transcendental power is out of scope — should error");
    }
}
