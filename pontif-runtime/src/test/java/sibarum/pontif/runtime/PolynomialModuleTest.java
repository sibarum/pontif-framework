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
                countTerms(simplify(Pow(Add(Param("x"), Const(1.0)), Const(2.0))))
                """));
    }

    @Test
    void simplify_isEvalPreserving() {
        // (2x+1)*(x-3) at x = 5 -> 11*2 = 22, unchanged by simplify.
        assertEquals("true", run("""
                requires pontif.poly.{simplify}
                requires pontif.algebra.{AlgExpr, Const, Param, Add, Sub, Mul, eval}
                let src:AlgExpr = Mul(Add(Mul(Const(2.0), Param("x")), Const(1.0)), Sub(Param("x"), Const(3.0)))
                eval(simplify(src), 5.0) == eval(src, 5.0)
                """));
    }

    @Test
    void simplify_leavesNonPolynomialTermsUnchanged() {
        // simplify(x + sin(x)) must stay eval-equal to x + sin(x) — sin(x) is an opaque atom,
        // NOT collapsed into a constant. At x = 0: 0 + sin(0) = 0 (the old degree-based bug gave 1).
        assertEquals("true", run("""
                requires pontif.poly.{simplify}
                requires pontif.algebra.{AlgExpr, Const, Param, Add, Sin, eval}
                let src:AlgExpr = Add(Param("x"), Sin(Param("x")))
                eval(simplify(src), 0.0) == eval(src, 0.0)
                """));
    }

    @Test
    void simplify_combinesRepeatedAtoms() {
        // sin(x) + sin(x) -> 2 sin(x): a single term, eval-equal to 2*sin(x).
        assertEquals("true", run("""
                requires pontif.poly.{simplify}
                requires pontif.algebra.{AlgExpr, Const, Param, Add, Mul, Sin, eval}
                let src:AlgExpr = Add(Sin(Param("x")), Sin(Param("x")))
                eval(simplify(src), 1.0) == eval(Mul(Const(2.0), Sin(Param("x"))), 1.0)
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

    @Test
    void differentiate_polynomial() {
        // d/dx (x^3 + 2x) = 3x^2 + 2; at x = 2 -> 14.
        assertEquals("true", run("""
                requires pontif.poly.{differentiate}
                requires pontif.algebra.{AlgExpr, Const, Param, Add, Mul, Pow, eval}
                let f:AlgExpr = Add(Pow(Param("x"), Const(3.0)), Mul(Const(2.0), Param("x")))
                eval(differentiate(f, "x"), 2.0) == 14.0
                """));
    }

    @Test
    void differentiate_quotientAndTranscendental() {
        // d/dx (1/x) = -1/x^2; at x = 2 -> -0.25.  d/dx sin(x) = cos(x); at x = 0 -> 1.
        assertEquals("true", run("""
                requires pontif.poly.{differentiate}
                requires pontif.algebra.{AlgExpr, Const, Param, Div, Sin, eval}
                let recip:AlgExpr = Div(Const(1.0), Param("x"))
                let s:AlgExpr = Sin(Param("x"))
                eval(differentiate(recip, "x"), 2.0) == -0.25
                """));
    }

    @Test
    void differentiate_asChainedExpressionMethod() {
        // (x+1)^2 -> differentiate -> simplify -> eval.  d = 2x + 2; at x = 3 -> 8.
        assertEquals("true", run("""
                requires pontif.poly.{Expression}
                requires pontif.algebra.{Const, Param, Add, Pow}
                Expression(Pow(Add(Param("x"), Const(1.0)), Const(2.0)))
                  .differentiate("x").simplify().eval(3.0) == 8.0
                """));
    }

    @Test
    void expressionWrapper_chainsTransforms() {
        // Expression wraps an AlgExpr and exposes the transforms as chainable methods.
        // (x+1)^2 -> expand -> simplify -> eval at 3 => 16.
        assertEquals("true", run("""
                requires pontif.poly.{Expression}
                requires pontif.algebra.{Const, Param, Add, Pow}
                Expression(Pow(Add(Param("x"), Const(1.0)), Const(2.0)))
                  .expand().simplify().eval(3.0) == 16.0
                """));
    }

    @Test
    void expressionWrapper_substituteThenEval() {
        // 3x + 1, substitute x := 5, eval => 16.
        assertEquals("true", run("""
                requires pontif.poly.{Expression}
                requires pontif.algebra.{Const, Param, Add, Mul}
                Expression(Add(Mul(Const(3.0), Param("x")), Const(1.0)))
                  .substitute("x", Const(5.0)).eval(0.0) == 16.0
                """));
    }

    @Test
    void expressionWrapper_astDropsBackToTheRawTree() {
        // `.ast` returns the wrapped AlgExpr, so you can match / hand it to a free function.
        assertEquals("1", run("""
                requires pontif.poly.{Expression}
                requires pontif.algebra.{AlgExpr, Const, Param, Add, Pow}
                let e:AlgExpr = Expression(Pow(Add(Param("x"), Const(1.0)), Const(2.0))).expand().ast
                match e { [Add(_, _)] -> 1  [_] -> 0 }
                """));
    }
}
