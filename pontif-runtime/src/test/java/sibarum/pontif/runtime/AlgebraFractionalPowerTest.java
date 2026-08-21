package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;

import java.math.BigDecimal;
import java.math.MathContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fractional powers in {@code eval}: a square root (and any rational power) is {@code Pow} with a
 * fractional exponent. The evaluation must be EXACT when the result is a terminating decimal
 * (perfect roots) and correctly rounded to the claimed DECIMAL128 precision otherwise — never the
 * old {@code Math.pow} double fallback.
 */
class AlgebraFractionalPowerTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private String run(String src) {
        var r = compiler.compile(src, "pow.ptf");
        if (r instanceof PontifCompiler.CompileResult.Failed f) return "COMPILE-FAIL: " + f;
        return new PontifRunner().run(r, Engine.INTERPRETER).text();
    }

    @Test
    void perfectSquareRoot_isExact() {
        assertEquals("true", run("""
                requires pontif.algebra.{Const, Pow, eval}
                eval(Pow(Const(4.0), Const(0.5)), 0.0) == 2.0
                """));
    }

    @Test
    void perfectSquareRoot_ofNonInteger_isExact() {
        // sqrt(2.25) = 1.5 exactly
        assertEquals("true", run("""
                requires pontif.algebra.{Const, Pow, eval}
                eval(Pow(Const(2.25), Const(0.5)), 0.0) == 1.5
                """));
    }

    @Test
    void perfectCubeRoot_viaExactRationalExponent_isExact() {
        // 8^(1/3) = 2 exactly — the exponent Div(1,3) is kept exact (not collapsed to 0.333...).
        assertEquals("true", run("""
                requires pontif.algebra.{Const, Div, Pow, eval}
                eval(Pow(Const(8.0), Div(Const(1.0), Const(3.0))), 0.0) == 2.0
                """));
    }

    @Test
    void fractionalPowerChain_isExact() {
        // 4^1.5 = (4^3)^(1/2) = sqrt(64) = 8 exactly
        assertEquals("true", run("""
                requires pontif.algebra.{Const, Pow, eval}
                eval(Pow(Const(4.0), Const(1.5)), 0.0) == 8.0
                """));
    }

    @Test
    void irrationalRoot_isAccurateToDecimal128_notDouble() {
        // sqrt(2): irrational -> correctly rounded to DECIMAL128, NOT double's ~17 digits.
        String out = run("""
                requires pontif.algebra.{Const, Pow, eval}
                eval(Pow(Const(2.0), Const(0.5)), 0.0)
                """);
        // The old lossy path produced exactly the double toString; the exact path must not.
        assertNotEquals("1.4142135623730951", out, "must not be the double Math.pow value");

        BigDecimal expected = new BigDecimal("2").sqrt(MathContext.DECIMAL128);
        BigDecimal actual = new BigDecimal(out.trim());
        // Full DECIMAL128 precision (34 significant digits), correctly rounded.
        assertTrue(actual.precision() >= 33, "expected DECIMAL128 precision, got " + actual.precision());
        assertEquals(0, actual.compareTo(expected),
                "sqrt(2) must be correctly rounded to DECIMAL128: expected " + expected + " got " + actual);
    }

    @Test
    void evenRootOfNegative_failsClosed() {
        // No real square root of a negative number -> honest runtime error, not a NaN / double.
        String out = run("""
                requires pontif.algebra.{Const, Pow, eval}
                eval(Pow(Const(-4.0), Const(0.5)), 0.0)
                """);
        assertTrue(out.toLowerCase().contains("not real") || out.toLowerCase().contains("real"),
                "even root of a negative should fail closed, got: " + out);
    }
}
