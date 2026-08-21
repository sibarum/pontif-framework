package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code evalSafe} — the TOTAL sibling of {@code eval}. Where strict {@code eval} fails closed at
 * a domain gap (a pole, an even root of a negative, a non-finite transcendental), {@code evalSafe}
 * yields the {@code Undefined} sentinel, so a consumer (the plotter) can sample across an
 * asymptote without aborting. Fail-closed surfaced as a value, not a crash — and NOT a fabricated
 * number: the honest quotient of {@code 1/0} does not exist, and the union return says exactly that.
 */
class AlgebraSafeEvalTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private String run(String src) {
        return new PontifRunner().run(
                compiler.compile(src, "safeeval.ptf"), Engine.INTERPRETER).text();
    }

    @Test
    void poleReturnsUndefined() {
        // 1/x at x = 0: a pole. evalSafe answers Undefined instead of dividing by zero.
        assertEquals("\"undef\"", run("""
                requires pontif.algebra.{AlgExpr, Const, Param, Div, Undefined, evalSafe}
                let e:AlgExpr = Div(Const(1.0), Param("x"))
                match evalSafe(e, 0.0) {
                  [Decimal]   -> "def"
                  [Undefined] -> "undef"
                }
                """));
    }

    @Test
    void definedPointReturnsDecimal() {
        // Away from the pole evalSafe behaves exactly like eval: 1/2 = 0.5, a real value.
        assertEquals("\"def\"", run("""
                requires pontif.algebra.{AlgExpr, Const, Param, Div, Undefined, evalSafe}
                let e:AlgExpr = Div(Const(1.0), Param("x"))
                match evalSafe(e, 2.0) {
                  [Decimal]   -> "def"
                  [Undefined] -> "undef"
                }
                """));
    }

    @Test
    void evenRootOfNegativeIsUndefined() {
        // sqrt(x) reflects to Pow(x, 0.5); at x = -4 the even root has no real value → Undefined.
        assertEquals("\"undef\"", run("""
                requires pontif.algebra.{AlgExpr, Const, Param, Pow, Undefined, evalSafe}
                let e:AlgExpr = Pow(Param("x"), Const(0.5))
                match evalSafe(e, -4.0) {
                  [Decimal]   -> "def"
                  [Undefined] -> "undef"
                }
                """));
    }

    @Test
    void strictEvalStillFailsClosedAtAPole() {
        // The strict evaluator is unchanged: a pole is reported as a runtime error (fail-closed),
        // never a fabricated number. The runner renders that as an error result rather than a value.
        String out = run("""
                requires pontif.algebra.{AlgExpr, Const, Param, Div, eval}
                let e:AlgExpr = Div(Const(1.0), Param("x"))
                eval(e, 0.0)
                """);
        assertTrue(out.contains("division by zero"),
                "strict eval should fail closed at a pole, got: " + out);
    }
}
