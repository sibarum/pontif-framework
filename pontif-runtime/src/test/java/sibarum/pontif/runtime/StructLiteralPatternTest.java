package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Positional literal field patterns: {@code [Ternion(z, 0, w)]} destructures
 * AND narrows in one pattern — the i-th literal clause constrains the i-th
 * declared field to {@code [@==literal]} without binding it. Binding and
 * constraining compose per field; a constrained field's name is NOT silently
 * bound (no accidental shadowing).
 */
class StructLiteralPatternTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String src) {
        return runner.run(compiler.compileAlt(src, "t.ptf"), Engine.INTERPRETER);
    }

    @Test
    void literalFieldPattern_constrainsAndDestructures() {
        // The ternion case: match the n==0 ternion, bind z and w.
        RunResult r = run("""
                struct Ternion(z:Decimal, n:Decimal, w:Decimal)
                method Ternion.inv():Ternion ->
                  match self {
                    [Ternion(z, 0, w)] -> Ternion(w, 0, z+1)
                    [_] -> Ternion(self.w, 1.0/self.n, self.z)
                  }
                Ternion(2,0,5).inv().w
                """);
        assertFalse(r.isError(), () -> "got: " + r.text());
        assertEquals("3", r.text());  // w = z+1 = 2+1
    }

    @Test
    void literalField_doesNotBind_noShadowing() {
        // The field is named n, the outer let is named n — the literal clause
        // must NOT bind the field, so `n` in the arm body is the outer 99.
        RunResult r = run("""
                struct Box(a:Int, n:Int)
                let n = 99
                function f(b:Box):Int -> match b {
                  [Box(a, 0)] -> a + n
                  [_] -> -1
                }
                f(Box(1, 0))
                """);
        assertFalse(r.isError(), () -> "got: " + r.text());
        assertEquals("100", r.text());
    }

    @Test
    void literalField_nonMatchingValue_takesNextArm() {
        RunResult r = run("""
                struct Box(a:Int, n:Int)
                function f(b:Box):Int -> match b {
                  [Box(a, 0)] -> a
                  [_] -> -1
                }
                f(Box(1, 7))
                """);
        assertFalse(r.isError(), () -> "got: " + r.text());
        assertEquals("-1", r.text());
    }

    @Test
    void decimalAndBoolLiterals_workInFieldPosition() {
        RunResult dec = run("""
                struct P(v:Decimal, k:Int)
                function f(p:P):Int -> match p {
                  [P(0.5, k)] -> k
                  [_] -> -1
                }
                f(P(0.5, 7))
                """);
        assertFalse(dec.isError(), () -> "got: " + dec.text());
        assertEquals("7", dec.text());

        RunResult bool = run("""
                struct Flag(on:Bool, k:Int)
                function f(p:Flag):Int -> match p {
                  [Flag(true, k)] -> k
                  [_] -> -1
                }
                f(Flag(true, 5))
                """);
        assertFalse(bool.isError(), () -> "got: " + bool.text());
        assertEquals("5", bool.text());
    }
}
