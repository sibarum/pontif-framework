package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AlgebraicDispatch (roadmap §5): the {@code [[Dispatch(Decimal):Decimal] & Algebraic]}
 * trait-view sort. A metareference whose referent carries an {@code assign proof
 * f:Algebraic} claim is stamped with this intersection by inference; it widens to a
 * plain {@code Dispatch} (a some-branch is-a) and carries the {@code Algebraic} marker.
 * This slice pins the sort's well-formedness and propagation through a parameter; the
 * {@code $f[Decimal].ast} member surface is the next slice.
 */
class AlgebraicDispatchTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private String run(String src) {
        return new PontifRunner().run(
                compiler.compileAlt(src, "algdispatch.ptf"), Engine.INTERPRETER).text();
    }

    @Test
    void algebraicDispatchParamSort_isWellFormedAndAlgebraicRefFits() {
        // The `& Algebraic` param sort validates, an algebraic metareference fits it
        // (propagation), and it widens to a plain Dispatch for astOf — end-to-end.
        assertEquals("true", run("""
                requires pontif.algebra.{astOf, eval}
                function poly(x:Decimal):Decimal -> x*x + 1.0
                assign proof poly:Algebraic
                function reflectAndEval(f:[[Dispatch(Decimal):Decimal] & Algebraic], x:Decimal):Decimal ->
                  eval(astOf(f), x)
                reflectAndEval($poly[Decimal], 5.0) == poly(5.0)
                """));
    }
}
