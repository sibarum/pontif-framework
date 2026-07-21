package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.module.BuiltinModules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code pontif.poly} builtin — a tame-polynomial CAS core written entirely in Pontif over
 * {@code pontif.algebra}'s {@code AlgExpr} union (no native calls). Pins the three public
 * functions ({@code substitute}, {@code expand}, {@code simplify}) end-to-end through the real
 * installed module, and that its source is retained for editor inspection like any builtin.
 */
class PolynomialModuleTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private String run(String src) {
        return new PontifRunner().run(
                compiler.compileAlt(src, "poly.ptf"), Engine.INTERPRETER).text();
    }

    @Test
    void substitute_replacesTheVariable() {
        // substitute x -> 5 in (x + 1), then eval -> 6
        assertEquals("true", run("""
                requires pontif.poly.{substitute}
                requires pontif.algebra.{AlgExpr, Const, Param, Add, eval}
                eval(substitute(Add(Param("x"), Const(1.0)), "x", Const(5.0)), 0.0) == 6.0
                """));
    }

    @Test
    void expand_isEvalPreserving() {
        // (x-1)*(x+1) = x^2 - 1; expanded form evals identically at x = 3 -> 8
        assertEquals("true", run("""
                requires pontif.poly.{expand}
                requires pontif.algebra.{AlgExpr, Const, Param, Add, Sub, Mul, eval}
                let src:AlgExpr = Mul(Sub(Param("x"), Const(1.0)), Add(Param("x"), Const(1.0)))
                eval(expand(src), 3.0) == eval(src, 3.0)
                """));
    }

    @Test
    void simplify_combinesLikeTerms() {
        // (x+1)^2 -> x^2 + 2x + 1: exactly 3 terms (expand alone would leave 4: x*x + x + x + 1).
        assertEquals("3.0", run("""
                requires pontif.poly.{simplify}
                requires pontif.algebra.{AlgExpr, Const, Param, Add, Pow}
                function countTerms(e:AlgExpr):Decimal -> match e {
                  [Add(l, r)] -> countTerms(l) + countTerms(r)
                  [_] -> 1.0
                }
                countTerms(simplify(Pow(Add(Param("x"), Const(1.0)), Const(2.0)), "x"))
                """));
    }

    @Test
    void simplify_isEvalPreserving() {
        // (2x+1)*(x-3) at x = 5 -> 11*2 = 22, unchanged by simplify.
        assertEquals("true", run("""
                requires pontif.poly.{simplify}
                requires pontif.algebra.{AlgExpr, Const, Param, Add, Sub, Mul, eval}
                let src:AlgExpr = Mul(Add(Mul(Const(2.0), Param("x")), Const(1.0)), Sub(Param("x"), Const(3.0)))
                eval(simplify(src, "x"), 5.0) == eval(src, 5.0)
                """));
    }

    @Test
    void moduleSource_isRetainedForEditorInspection() {
        // The builtin's Pontif source is registered (Extensions.install -> registerExtensionModule
        // with source), so the editor's Definition view can reflect it like any builtin.
        String src = BuiltinModules.sourceOf("pontif.poly");
        assertNotNull(src, "pontif.poly source must be retained for inspection");
        assertTrue(src.contains("function simplify"), "source should contain the simplify definition");
    }
}
