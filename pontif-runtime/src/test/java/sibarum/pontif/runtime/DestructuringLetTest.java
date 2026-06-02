package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Destructuring let — {@code let [Ternion(a, b, c)] = expr} — at both levels,
 * desugared to a single-arm match so the totality checker enforces the let
 * rule: the pattern must be <b>proven total</b> over the value's sort
 * (trivially — a bare destructure — or via the kernel). A refutable pattern is
 * a compile error, not a runtime gamble.
 */
class DestructuringLetTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String src) {
        return runner.run(compiler.compileAlt(src, "t.ptf"), Engine.INTERPRETER);
    }

    @Test
    void topLevel_destructuringLet_bindsFields() {
        // James's originating example, verbatim shape.
        RunResult r = run("""
                struct Ternion(z:Decimal, n:Decimal, w:Decimal)
                method Ternion.inv():Ternion ->
                  match self {
                    [Ternion(z, 0, w)] -> Ternion(w, 0, z+1)
                    [_] -> Ternion(self.w, 1.0/self.n, self.z)
                  }
                let [Ternion(first, second, third)] = Ternion(2,0,5).inv()
                first + third
                """);
        assertFalse(r.isError(), () -> "got: " + r.text());
        assertEquals("8.0", r.text());  // inv() = Ternion(5,0,3); 5 + 3, Decimal display
    }

    @Test
    void expressionLevel_destructuringLet_bindsFields() {
        RunResult r = run("""
                struct Pair(a:Int, b:Int)
                function sum(p:Pair):Int -> let [Pair(a, b)] = p  a + b
                sum(Pair(3, 4))
                """);
        assertFalse(r.isError(), () -> "got: " + r.text());
        assertEquals("7", r.text());
    }

    @Test
    void refutablePattern_inLet_isCompileError() {
        // p's b field could be anything — [Pair(a, 0)] is refutable here.
        RunResult r = run("""
                struct Pair(a:Int, b:Int)
                function f(p:Pair):Int -> let [Pair(a, 0)] = p  a
                f(Pair(3, 0))
                """);
        assertTrue(r.isError(), "refutable let pattern must be rejected at compile time");
        assertTrue(r.text().contains("exhaustive") || r.text().contains("cannot prove"),
                () -> "expected a totality rejection; got: " + r.text());
    }

    @Test
    void provableNarrowing_inLet_compiles() {
        // Same pattern, but the RHS provably has b == 0 — "proven correct".
        RunResult r = run("""
                struct Pair(a:Int, b:Int)
                let [Pair(a, 0)] = Pair(3, 0)
                a
                """);
        assertFalse(r.isError(), () -> "provable narrowing should compile; got: " + r.text());
        assertEquals("3", r.text());
    }

    @Test
    void unprovableNarrowing_inTopLevelLet_isCompileError() {
        // Same pattern again, but the RHS provably has b == 7 — rejected.
        RunResult r = run("""
                struct Pair(a:Int, b:Int)
                let [Pair(a, 0)] = Pair(3, 7)
                a
                """);
        assertTrue(r.isError(), "b is provably 7, not 0 — the let must be rejected");
    }
}
