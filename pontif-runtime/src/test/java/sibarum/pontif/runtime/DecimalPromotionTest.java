package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Int-literal → Decimal promotion at declared boundaries ({@code DecimalPromotion}),
 * and the clear, origin-carrying error for the value-level mixing that is NOT
 * promoted. Born from {@code pontif-playground/examples/ternion.ptf}, which
 * constructed Decimal fields with Int literals and died with a raw
 * {@code ClassCastException} (no file/line).
 */
class DecimalPromotionTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String src) {
        return runner.run(compiler.compileAlt(src, "t.ptf"), PontifRunner.Engine.INTERPRETER);
    }

    @Test
    void ternionExample_runsEndToEnd() {
        // The originating example (inlined): Decimal fields built from Int
        // literals, then decimal arithmetic across them.
        RunResult r = run("""
                struct Ternion(z:Decimal, n:Decimal, w:Decimal)

                function *(left:Ternion, right:Ternion):Ternion -> {
                  let z = left.z*right.z + left.z*right.n + left.n*right.z
                  let n = left.n*right.n + left.z*right.w + left.w*right.z
                  let w = left.w*right.w + left.w*right.n + left.n*right.w
                  Ternion(z, n, w)
                }

                let prod = Ternion(0,1.2,0)*Ternion(0,1.5,0)
                prod.n
                """);
        assertFalse(r.isError(), () -> "expected a clean run, got: " + r.text());
        assertEquals("1.80", r.text());
    }

    @Test
    void intLiteral_promotesIntoDecimalField() {
        RunResult r = run("""
                struct Box(v:Decimal)
                Box(1).v + 0.5
                """);
        assertFalse(r.isError(), () -> "expected promotion, got: " + r.text());
        assertEquals("1.5", r.text());
    }

    @Test
    void intLiteral_promotesIntoNarrowedDecimalField() {
        // Promotion happens before the narrow check, so the promoted 1 (> 0)
        // satisfies the field's sign narrow.
        RunResult r = run("""
                struct Pos(v:[Decimal:@>0])
                Pos(1).v
                """);
        assertFalse(r.isError(), () -> "expected promotion + narrow pass, got: " + r.text());
        assertEquals("1", r.text());
    }

    @Test
    void mixedIntValueAndDecimal_isClearErrorWithOrigin() {
        // An Int VALUE (not a literal at a Decimal boundary) meeting a Decimal
        // is not auto-promoted — and the error must be a Pontif error with an
        // origin (file/line), never a raw ClassCastException.
        RunResult r = run("""
                function f(x:Int):Decimal -> x + 1.5
                f(1)
                """);
        assertTrue(r.isError(), "mixed Int value/Decimal arithmetic should error");
        assertTrue(r.text().contains("mixed"),
                () -> "expected the mixed-operand message, got: " + r.text());
        assertFalse(r.text().contains("ClassCastException"),
                () -> "raw Java exception leaked: " + r.text());
        assertTrue(r.origin().isPresent() || r.text().contains("t.ptf"),
                () -> "error should carry an origin (file/line); got: " + r.text()
                        + " origin=" + r.origin());
    }
}
