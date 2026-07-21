package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The <b>hand-built AST</b> contract: an {@code AlgExpr} tree constructed directly from the
 * exported {@code pontif.algebra} node structs — with no reflection ({@code astOf}) anywhere —
 * evaluates and pattern-matches exactly like a reflected one. The evaluator ({@code evalNode})
 * is purely structural (it dispatches on the node's nominal and reads members by name), so a
 * reflected tree and a hand-built tree are indistinguishable to it. This is the dual of the
 * {@code match}-inspection surface in {@link AlgebraAstSurfaceTest}: reflection is one producer
 * of the AST, direct construction is the other, and both feed the same {@code eval}/{@code evalAt}
 * consumers. (docs/metatypes.md: "the AST is an ordinary Pontif trait union … a program can
 * match on it and write its own evaluator.")
 */
class AlgebraManualConstructionTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private String run(String src) {
        return new PontifRunner().run(
                compiler.compileAlt(src, "algebra.ptf"), Engine.INTERPRETER).text();
    }

    @Test
    void handBuiltAst_evaluatesOverOneVariable() {
        // e = 2*x + 1, built directly from the node structs; eval binds every Param to x.
        assertEquals("true", run("""
                requires pontif.algebra.{AlgExpr, Const, Param, Add, Mul, eval}
                let e:AlgExpr = Add(Mul(Const(2.0), Param("x")), Const(1.0))
                eval(e, 3.0) == 7.0
                """));
    }

    @Test
    void handBuiltAst_evaluatesOverNamedVariables() {
        // e = x*y + x, built by hand; evalAt binds each Param by its `name` field from the point.
        assertEquals("true", run("""
                requires pontif.algebra.{AlgExpr, Const, Param, Add, Mul, evalAt}
                let e:AlgExpr = Add(Mul(Param("x"), Param("y")), Param("x"))
                evalAt(e, {x = 3.0, y = 4.0}) == 15.0
                """));
    }

    @Test
    void handBuiltAst_isInspectableWithMatch() {
        // Construction is the dual of the match surface — a hand-built node destructures the same.
        assertEquals("1", run("""
                requires pontif.algebra.{AlgExpr, Const, Param, Add}
                let e:AlgExpr = Add(Const(1.0), Param("x"))
                match e {
                  [Add(_, _)] -> 1
                  [_]         -> 0
                }
                """));
    }

    @Test
    void handBuiltAst_matchesTheReflectedTreeForTheSameExpression() {
        // The two producers agree: reflecting `poly` and hand-building poly's body evaluate equal.
        assertEquals("true", run("""
                requires pontif.algebra.{AlgExpr, Const, Param, Add, Mul, eval}
                function poly(x:Decimal):Decimal -> 2.0*x + 1.0
                assign proof poly:Algebraic
                let built:AlgExpr = Add(Mul(Const(2.0), Param("x")), Const(1.0))
                eval(built, 3.0) == eval($poly[Decimal].ast, 3.0)
                """));
    }
}
