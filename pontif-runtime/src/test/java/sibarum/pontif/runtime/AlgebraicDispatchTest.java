package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        // end-to-end through a function boundary. (Static REJECTION of a non-algebraic ref
        // at such a param is the deferred §1d propagation gate; the DIRECT `$f[…].ast`
        // guarantee is statically enforced — see AlgebraAstSurfaceTest.)
        assertEquals("true", run("""
                requires pontif.algebra.{eval, Algebraic}
                function poly(x:Decimal):Decimal -> x*x + 1.0
                assign proof poly:Algebraic
                function reflectAndEval(f:Algebraic, x:Decimal):Decimal ->
                  eval(f.ast, x)
                reflectAndEval($poly[Decimal], 5.0) == poly(5.0)
                """));
    }
}
