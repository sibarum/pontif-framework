package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The {@code $f[Decimal].ast} surface (docs/dispatch-method-elimination.md, Stage E2): a
 * metareference is a first-class object (a {@code RecordValue} nominal AlgebraicDispatch
 * when its referent is proven algebraic) whose {@code .ast} attribute — an {@code Algebraic}
 * trait member — reflects the referent into a first-class {@code AlgExpr} AST. This is the
 * ONLY surface: {@code astOf} is non-exported, and {@code .ast} on a non-algebraic reference
 * is a compile error. Proves E2's acid test: the whole feature rides E1's general call-kind
 * machinery — a capability the head type is-a, plus a stock trait attribute — with no
 * bespoke type-system special-casing.
 */
class AlgebraAstSurfaceTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private String run(String src) {
        return new PontifRunner().run(
                compiler.compileAlt(src, "algebra.ptf"), Engine.INTERPRETER).text();
    }

    @Test
    void astSurface_evaluatesToTheDirectCall() {
        assertEquals("true", run("""
                requires pontif.algebra.{eval}
                function poly(x:Decimal):Decimal -> x*x + 2.0*x + 1.0
                assign proof poly:Algebraic
                eval($poly[Decimal].ast, 3.0) == poly(3.0)
                """));
    }

    @Test
    void evalMethod_onTheMetareference_matchesTheDirectCall() {
        // THE ULTIMATE TEST: `eval` is a METHOD on the AlgebraicDispatch reference —
        // `$poly[Decimal].eval(3.0)` — added purely by declaring a trait method + impl in the
        // algebra module, with ZERO type-system code change. The metareference behaves as a
        // first-class object: a member that reflects (`.ast`) and a method that computes.
        assertEquals("true", run("""
                requires pontif.algebra.{Algebraic}
                function poly(x:Decimal):Decimal -> x*x + 2.0*x + 1.0
                assign proof poly:Algebraic
                $poly[Decimal].eval(3.0) == poly(3.0)
                """));
    }

    @Test
    void astSurface_isInspectableWithMatch() {
        // poly's body ((x*x + 2.0*x) + 1.0) has an Add at its root.
        assertEquals("1", run("""
                requires pontif.algebra.{AlgExpr, Add}
                function poly(x:Decimal):Decimal -> x*x + 2.0*x + 1.0
                assign proof poly:Algebraic
                let e:AlgExpr = $poly[Decimal].ast
                match e {
                  [Add(_, _)] -> 1
                  [_]         -> 0
                }
                """));
    }

    @Test
    void astOnNonAlgebraicReference_isACompileError() {
        // `inc` carries no `assign proof inc:Algebraic`, so `$inc[Int]` narrows to the plain
        // Dispatch nominal — which has no `.ast` member. Statically rejected.
        var r = compiler.compileAlt("""
                function inc(x:Int):Int -> x + 1
                $inc[Int].ast
                """, "algebra.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Failed.class, r,
                "`.ast` on a non-algebraic metareference must be a compile error, got " + r);
    }

    @Test
    void astOf_isNotExported() {
        // The reflection primitive is hidden — `$f[Decimal].ast` is the only surface.
        var r = compiler.compileAlt("""
                requires pontif.algebra.{astOf}
                0
                """, "algebra.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Failed.class, r,
                "astOf must not be importable (non-exported), got " + r);
    }
}
