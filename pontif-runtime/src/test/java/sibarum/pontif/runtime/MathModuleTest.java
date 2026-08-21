package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The builtin math library: pontif.math (SPIR-V GLSL.std.450 set) + pontif.math.ext (CPU extras),
 * both installed by default. Verifies functions run, precision is honest (double-bounded, not a
 * spurious exact-looking expansion), and the SPIR-V/ext module boundary holds.
 */
class MathModuleTest {

    private String run(String src) {
        return new PontifRunner().run(
                new PontifCompiler().compile(src, "t.ptf"), Engine.INTERPRETER).text();
    }

    @Test
    void spirvFunctions_run() {
        assertEquals("true", run("requires pontif.math.{sqrt}\nsqrt(4.0) == 2.0"));
        assertEquals("true", run("requires pontif.math.{sin}\nsin(0.0) == 0.0"));
        assertEquals("true", run("requires pontif.math.{atan2}\natan2(0.0, 1.0) == 0.0"));
        assertEquals("true", run("requires pontif.math.{pow}\npow(2.0, 10.0) == 1024.0"));
    }

    @Test
    void exactCommonOps_areExact() {
        assertEquals("true", run("requires pontif.math.{floor}\nfloor(2.7) == 2.0"));
        assertEquals("true", run("requires pontif.math.{ceil}\nceil(2.1) == 3.0"));
        assertEquals("true", run("requires pontif.math.{abs}\nabs(-3.0) == 3.0"));
        assertEquals("true", run("requires pontif.math.{max}\nmax(3.0, 5.0) == 5.0"));
        assertEquals("true", run("requires pontif.math.{clamp}\nclamp(9.0, 0.0, 5.0) == 5.0"));
    }

    @Test
    void constantsImport() {
        // Constants are nullary functions (module-level `let`s don't import across modules).
        assertEquals("true", run("requires pontif.math.{pi}\npi() > 3.14"));
        assertEquals("true", run("requires pontif.math.{tau}\ntau() > 6.28"));
    }

    @Test
    void transcendental_precisionIsHonest_notSpurious() {
        // sqrt(2) at double precision is ~17 significant digits — NOT a 50-digit expansion
        // pretending to be exact.
        String s = run("requires pontif.math.{sqrt}\nsqrt(2.0)");
        assertTrue(s.startsWith("1.41421356"), () -> "value: " + s);
        assertTrue(s.length() <= 20, () -> "expected double-bounded precision, got " + s.length()
                + " chars: " + s);
    }

    @Test
    void extIntegerUtilities_run() {
        assertEquals("true", run("requires pontif.math.ext.{gcd}\ngcd(12, 8) == 4"));
        assertEquals("true", run("requires pontif.math.ext.{lcm}\nlcm(4, 6) == 12"));
        assertEquals("true", run("requires pontif.math.ext.{factorial}\nfactorial(5) == 120"));
        assertEquals("true", run("requires pontif.math.ext.{isqrt}\nisqrt(17) == 4"));
        assertEquals("true", run("requires pontif.math.ext.{choose}\nchoose(5, 2) == 10"));
    }

    @Test
    void spirvBoundary_gcdNotInPontifMath() {
        // gcd is CPU-only (no GLSL opcode) — it lives in pontif.math.ext, NOT pontif.math.
        CompileResult r = new PontifCompiler().compile(
                "requires pontif.math.{gcd}\ngcd(12, 8)", "t.ptf");
        assertInstanceOf(CompileResult.Failed.class, r,
                "gcd must not be importable from pontif.math (it's a pontif.math.ext function)");
    }
}
