package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bound propagation for operator contract members: inside a generic body, an
 * operator applied to a {@code [type E:Trait]}-bounded value is typed by the
 * trait's contract ({@code +:[Dispatch(this.type,this.type):this.type]} ⟹ result
 * E), and an operator the bound does NOT promise is a compile error — so operator
 * use over an abstract type is decidable at definition time.
 */
class OperatorBoundPropagationTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        var r = runner.run(compiler.compileAlt(src, "op-bound.ptf"), Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "expected success; got: " + r.text());
        return r.text();
    }

    private String reject(String src) {
        CompileResult r = compiler.compileAlt(src, "op-bound.ptf");
        assertInstanceOf(CompileResult.Failed.class, r, "expected compile failure");
        return ((CompileResult.Failed) r).error().text();
    }

    @Test
    void genericBodyUsesBoundOperator_compilesAndRuns() {
        assertEquals("4", run("""
                trait Numeric{ +:[Dispatch(this.type, this.type):this.type] }
                struct Vec(x:Int, y:Int)
                function +(a:Vec, b:Vec):Vec -> Vec(a.x + b.x, a.y + b.y)
                assign trait Vec:Numeric { }
                function sum[type E:Numeric](a:E, b:E):E -> a + b
                sum(Vec(1, 2), Vec(3, 4)).x
                """));
    }

    @Test
    void letChainPropagatesTypeParam_composes() {
        // `let c = a + b` gives c:E (the contract result is the self type), so the
        // following `c + a` is also checked/typed over E — reach via narrowing.
        assertEquals("5", run("""
                trait Numeric{ +:[Dispatch(this.type, this.type):this.type] }
                struct Vec(x:Int, y:Int)
                function +(a:Vec, b:Vec):Vec -> Vec(a.x + b.x, a.y + b.y)
                assign trait Vec:Numeric { }
                function poly[type E:Numeric](a:E, b:E):E ->
                  let c = a + b
                  c + a
                poly(Vec(1, 2), Vec(3, 4)).x
                """));
    }

    @Test
    void genericBodyUsesUnboundedOperator_rejected() {
        String err = reject("""
                trait Numeric{ +:[Dispatch(this.type, this.type):this.type] }
                struct Vec(x:Int, y:Int)
                function +(a:Vec, b:Vec):Vec -> Vec(a.x + b.x, a.y + b.y)
                function -(a:Vec, b:Vec):Vec -> Vec(a.x - b.x, a.y - b.y)
                assign trait Vec:Numeric { }
                function diff[type E:Numeric](a:E, b:E):E -> a - b
                diff(Vec(5, 6), Vec(3, 4)).x
                """);
        assertTrue(err.contains("-") || err.contains("Numeric"), () -> err);
    }
}
