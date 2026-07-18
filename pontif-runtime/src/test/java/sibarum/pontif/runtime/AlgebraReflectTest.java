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
                new PontifCompiler().compileAlt(src, "algebra.ptf"), Engine.INTERPRETER).text();
    }

    @Test
    void astOfThenEval_matchesTheDirectCall() {
        assertEquals("true", run("""
                requires pontif.algebra.{astOf, eval}
                function poly(x:Decimal):Decimal -> x*x + 2.0*x + 1.0
                assign proof poly:Algebraic
                eval(astOf($poly[Decimal]), 3.0) == poly(3.0)
                """));
    }

    @Test
    void ast_isInspectableWithMatch() {
        // poly's body ((x*x + 2.0*x) + 1.0) has an Add at its root.
        assertEquals("1", run("""
                requires pontif.algebra.{astOf, AlgExpr, Add}
                function poly(x:Decimal):Decimal -> x*x + 2.0*x + 1.0
                assign proof poly:Algebraic
                let e:AlgExpr = astOf($poly[Decimal])
                match e {
                  [Add(_, _)] -> 1
                  [_]         -> 0
                }
                """));
    }

    @Test
    void nestedAlgebraicCall_isInlinedAndEvaluates() {
        assertEquals("true", run("""
                requires pontif.algebra.{astOf, eval}
                function sq(x:Decimal):Decimal -> x*x
                assign proof sq:Algebraic
                function poly(x:Decimal):Decimal -> sq(x) + 1.0
                assign proof poly:Algebraic
                eval(astOf($poly[Decimal]), 4.0) == poly(4.0)
                """));
    }

    @Test
    void reflectionWorksOnAPassedInFunctionValue() {
        // The AST is produced from a function VALUE handed to another function —
        // the payoff of first-class, reflectable Dispatch values.
        assertEquals("true", run("""
                requires pontif.algebra.{astOf, eval, AlgExpr}
                function poly(x:Decimal):Decimal -> x*x + 1.0
                assign proof poly:Algebraic
                function reflectAndEval(f:[Dispatch(Decimal):Decimal], x:Decimal):Decimal ->
                  eval(astOf(f), x)
                reflectAndEval($poly[Decimal], 5.0) == poly(5.0)
                """));
    }

    @Test
    void division_evaluates() {
        assertEquals("true", run("""
                requires pontif.algebra.{astOf, eval}
                function f(x:Decimal):Decimal -> x / 2.0
                assign proof f:Algebraic
                eval(astOf($f[Decimal]), 7.0) == f(7.0)
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
