package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code assign proof f:Algebraic} marker: a refinement on the function
 * itself. The body must be built only from the algebraic fragment (arithmetic,
 * parameters, local lets, field access, and calls to <b>other algebraic
 * functions</b>) and the algebraic call-graph must be acyclic (no recursion).
 * A false claim is a hard compile error — the substrate for runtime AST
 * reflection (pontif.algebra).
 */
class AlgebraicMarkerTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private void assertCompiles(String src) {
        CompileResult r = compiler.compileAlt(src, "algebraic.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "expected compile success; got: "
                        + ((CompileResult.Failed) r).error().text());
    }

    private String assertRejected(String src) {
        CompileResult r = compiler.compileAlt(src, "algebraic.ptf");
        assertInstanceOf(CompileResult.Failed.class, r, "expected compile failure");
        return ((CompileResult.Failed) r).error().text();
    }

    @Test
    void polynomial_isAlgebraic() {
        assertCompiles("""
                function poly(x:Decimal):Decimal -> x*x + 2.0*x + 1.0
                assign proof poly:Algebraic
                poly(3.0)
                """);
    }

    @Test
    void nestedCallToAnotherAlgebraicFunction_isAlgebraic() {
        assertCompiles("""
                function sq(x:Decimal):Decimal -> x*x
                assign proof sq:Algebraic
                function poly(x:Decimal):Decimal -> sq(x) + 1.0
                assign proof poly:Algebraic
                poly(3.0)
                """);
    }

    @Test
    void divisionAndPower_areAlgebraic() {
        assertCompiles("""
                function f(x:Decimal):Decimal -> x^3.0 / 2.0
                assign proof f:Algebraic
                f(4.0)
                """);
    }

    @Test
    void comparisonBody_isRejected() {
        String err = assertRejected("""
                function f(x:Int):Bool -> x > 0
                assign proof f:Algebraic
                42
                """);
        assertTrue(err.contains("f") && err.toLowerCase().contains("algebraic"),
                () -> "expected an algebraic rejection mentioning 'f'; got: " + err);
    }

    @Test
    void matchBody_isRejected() {
        String err = assertRejected("""
                function f(x:Int):Int -> match x {
                  [@>0] -> 1
                  [_]   -> 0
                }
                assign proof f:Algebraic
                42
                """);
        assertTrue(err.contains("f"),
                () -> "expected a rejection mentioning 'f'; got: " + err);
    }

    @Test
    void callToNonAlgebraicFunction_isRejected() {
        String err = assertRejected("""
                function sq(x:Decimal):Decimal -> x*x
                function poly(x:Decimal):Decimal -> sq(x) + 1.0
                assign proof poly:Algebraic
                poly(3.0)
                """);
        assertTrue(err.contains("poly") && err.contains("sq"),
                () -> "expected rejection naming 'poly' and its non-algebraic callee 'sq'; got: " + err);
    }

    @Test
    void directRecursion_isRejected() {
        String err = assertRejected("""
                function f(x:Int):Int -> f(x) + 1
                assign proof f:Algebraic
                42
                """);
        assertTrue(err.contains("f") && err.toLowerCase().contains("recurs"),
                () -> "expected a recursion rejection mentioning 'f'; got: " + err);
    }

    @Test
    void mutualRecursion_isRejected() {
        String err = assertRejected("""
                function f(x:Int):Int -> g(x) + 1
                assign proof f:Algebraic
                function g(x:Int):Int -> f(x) + 1
                assign proof g:Algebraic
                42
                """);
        assertTrue(err.toLowerCase().contains("recurs"),
                () -> "expected a recursion rejection; got: " + err);
    }
}
