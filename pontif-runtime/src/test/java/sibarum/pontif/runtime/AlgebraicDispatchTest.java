package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * AlgebraicDispatch (docs/dispatch-method-elimination.md E2): a metareference whose
 * referent carries an {@code assign proof f:Algebraic} claim narrows to the concrete
 * builtin nominal {@code AlgebraicDispatch} (is-a both {@code Dispatch} and the real
 * {@code Algebraic} trait). This pins that it fits an {@code Algebraic} parameter
 * (propagation), and that a NON-algebraic reference does NOT — the guarantee travels
 * with the value's type, not a marker intersection.
 */
class AlgebraicDispatchTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private String run(String src) {
        return new PontifRunner().run(
                compiler.compileAlt(src, "algdispatch.ptf"), Engine.INTERPRETER).text();
    }

    @Test
    void algebraicMetareference_fitsAnAlgebraicParameter_andReflects() {
        // An algebraic metareference fits an `Algebraic` param and its `.ast` reflects —
        // end-to-end through a function boundary.
        assertEquals("true", run("""
                requires pontif.algebra.{eval, Algebraic}
                function poly(x:Decimal):Decimal -> x*x + 1.0
                assign proof poly:Algebraic
                function reflectAndEval(f:Algebraic, x:Decimal):Decimal ->
                  eval(f.ast, x)
                reflectAndEval($poly[Decimal], 5.0) == poly(5.0)
                """));
    }

    @Test
    void nonAlgebraicMetareference_isRejectedAtAnAlgebraicParameter() {
        // The §1d propagation gate: the algebraic guarantee is a TYPE, so a plain
        // metareference (Dispatch, not AlgebraicDispatch) passed where Algebraic is required
        // is a COMPILE error — the guarantee travelling THROUGH a parameter, not just the
        // direct `$f[…].ast`.
        var r = compiler.compileAlt("""
                requires pontif.algebra.{eval, Algebraic}
                function inc(x:Decimal):Decimal -> x + 1.0
                function reflectAndEval(f:Algebraic, x:Decimal):Decimal -> eval(f.ast, x)
                reflectAndEval($inc[Decimal], 1.0)
                """, "algdispatch.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Failed.class, r,
                "a non-algebraic reference must not satisfy an Algebraic parameter, got " + r);
    }
}
