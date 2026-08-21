package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Tuple patterns may constrain a slot by value — `[(0.0, 0.0)]`, `[(0, y)]`,
 * `[([Int:@>0], y)]` — not just bind it, mirroring the struct form `[Point(0, y)]`.
 * A literal slot's base comes from the literal's own kind; the slot is occupied
 * but binds nothing (verdict C). (Bare-paren `(…)` patterns stay unsupported: `[`
 * is never postfix in Pontif but `(` is, so an unbracketed pattern collides with
 * application — the bracket/paren law's own reasoning.)
 */
class TuplePatternConstraintTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "tup.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        return runner.run(r, PontifRunner.Engine.INTERPRETER).text();
    }

    @Test
    void literalComponents_match() {
        assertEquals("1", run("""
                function f(p:[{Decimal, Decimal}]):Int -> match p {
                  [{0.0, 0.0}] -> 1
                  [_]          -> 0
                }
                f({0.0, 0.0})
                """));
    }

    @Test
    void literalComponents_noMatch() {
        assertEquals("0", run("""
                function f(p:[{Decimal, Decimal}]):Int -> match p {
                  [{0.0, 0.0}] -> 1
                  [_]          -> 0
                }
                f({0.0, 1.0})
                """));
    }

    @Test
    void mixedLiteralAndBinder() {
        // First slot pinned to 0, second bound to y.
        assertEquals("5", run("""
                function f(p:[{Int, Int}]):Int -> match p {
                  [{0, y}] -> y
                  [_]      -> -1
                }
                f({0, 5})
                """));
    }

    @Test
    void explicitRefinedComponent() {
        assertEquals("9", run("""
                function f(p:[{Int, Int}]):Int -> match p {
                  [{[Int:@>0], y}] -> y
                  [_]              -> -1
                }
                f({3, 9})
                """));
    }

    @Test
    void allBinders_stillWork() {
        // The original bind-only form is unchanged.
        assertEquals("7", run("""
                function f(p:[{Int, Int}]):Int -> match p {
                  [{a, b}] -> a + b
                }
                f({3, 4})
                """));
    }

    @Test
    void constructorComponents_bindNestedFields() {
        // A tuple of struct patterns: a pinned slot constrains, and inner field
        // binders (y, n, m) reach the arm body through the recursive destructure.
        assertEquals("7", run("""
                struct P(a:Int, b:Int)
                function f(p:[{P, P}]):Int -> match p {
                  [{P(0, y), P(n, m)}] -> y + n + m
                  [_]                  -> -1
                }
                f({P(0, 3), P(2, 2)})
                """));
    }

    @Test
    void constructorComponents_noMatchFallsThrough() {
        // _0 must be a P with a==0; (P(1,_), _) misses and takes the default arm.
        assertEquals("-1", run("""
                struct P(a:Int, b:Int)
                function f(p:[{P, P}]):Int -> match p {
                  [{P(0, y), P(n, m)}] -> y + n + m
                  [_]                  -> -1
                }
                f({P(1, 3), P(2, 2)})
                """));
    }
}
