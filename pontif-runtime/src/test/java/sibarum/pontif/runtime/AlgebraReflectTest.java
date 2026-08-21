package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The runtime side of algebraic / differential programming (pontif.algebra):
 * reflect an {@code assign proof f:Algebraic} function into a first-class
 * {@code AlgExpr} AST with {@code astOf($f[Decimal])}, inspect it with {@code match},
 * and evaluate it with {@code eval}. Also exercises the first-class-function fix:
 * applying a metareference reached as a bare expression.
 */
class AlgebraReflectTest {

    private String run(String src) {
        return new PontifRunner().run(
                new PontifCompiler().compile(src, "algebra.ptf"), Engine.INTERPRETER).text();
    }

    @Test
    void astOfThenEval_matchesTheDirectCall() {
        // The `.ast` surface reflects the algebraic function; astOf is non-exported (E2).
        assertEquals("true", run("""
                requires pontif.algebra.{eval}
                function poly(x:Decimal):Decimal -> x*x + 2.0*x + 1.0
                assign proof poly:Algebraic
                eval($poly[Decimal].ast, 3.0) == poly(3.0)
                """));
    }

    @Test
    void ast_isInspectableWithMatch() {
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
    void nestedAlgebraicCall_isInlinedAndEvaluates() {
        assertEquals("true", run("""
                requires pontif.algebra.{eval}
                function sq(x:Decimal):Decimal -> x*x
                assign proof sq:Algebraic
                function poly(x:Decimal):Decimal -> sq(x) + 1.0
                assign proof poly:Algebraic
                eval($poly[Decimal].ast, 4.0) == poly(4.0)
                """));
    }

    @Test
    void reflectionWorksOnAPassedInFunctionValue() {
        // The AST is produced from a metareference handed to another function through an
        // Algebraic parameter — the algebraic guarantee travels with the value's type, and
        // `.ast` reads it (a non-algebraic reference wouldn't type-check as Algebraic).
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
    void division_evaluates() {
        assertEquals("true", run("""
                requires pontif.algebra.{eval}
                function f(x:Decimal):Decimal -> x / 2.0
                assign proof f:Algebraic
                eval($f[Decimal].ast, 7.0) == f(7.0)
                """));
    }

    @Test
    void returnedMetareference_appliedAsBareExpression() {
        // The first-class fix: mk() returns a metareference, applied directly (not
        // laundered through a named let). Previously evalApply threw on a DispatchValue.
        assertEquals("6", run("""
                function inc(x:Int):Int -> x + 1
                function mk():[Dispatch(Int):Int] -> $inc[Int]
                mk()(5)
                """));
    }
}
