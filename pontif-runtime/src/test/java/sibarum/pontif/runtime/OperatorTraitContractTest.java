package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for operator contract members on traits (dispatch-unification
 * B1; docs/traits.md "Operator contract members"). A trait may name an operator as
 * a {@code [Dispatch(this.type, this.type):this.type]} member — a mechanism-1
 * bound. At {@code assign trait T:Trait} the compiler verifies a coherent overload
 * {@code op(T, T):T} is declared (the operator is *witnessed*, not implemented in
 * the block); a missing or wrong-shaped witness is a compile error. This is what
 * makes operator compatibility decidable at definition time rather than at a
 * runtime dispatch miss.
 */
class OperatorTraitContractTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        var r = runner.run(compiler.compile(src, "op-trait.ptf"), Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "expected success; got: " + r.text());
        return r.text();
    }

    private String reject(String src) {
        CompileResult r = compiler.compile(src, "op-trait.ptf");
        assertInstanceOf(CompileResult.Failed.class, r, "expected compile failure");
        return ((CompileResult.Failed) r).error().text();
    }

    @Test
    void witnessedOperator_compilesAndRuns() {
        // `+` is witnessed by the free overload; the empty impl block carries the
        // satisfaction claim, and `v + v` routes to that overload.
        assertEquals("2", run("""
                trait Numeric{ +:[Dispatch(this.type, this.type):this.type] }
                struct Vector(x:Int, y:Int)
                function +(a:Vector, b:Vector):Vector -> Vector(a.x + b.x, a.y + b.y)
                assign trait Vector:Numeric { }
                let v = Vector(1, 2)
                (v + v).x
                """));
    }

    @Test
    void missingOperatorOverload_rejected() {
        String err = reject("""
                trait Numeric{ +:[Dispatch(this.type, this.type):this.type] }
                struct Vector(x:Int, y:Int)
                assign trait Vector:Numeric { }
                0
                """);
        assertTrue(err.contains("requires operator '+'"), () -> err);
    }

    @Test
    void wrongReturnType_notWitnessed_rejected() {
        // `+(Vector, Vector):Int` is not the homogeneous `(T,T):T` shape.
        String err = reject("""
                trait Numeric{ +:[Dispatch(this.type, this.type):this.type] }
                struct Vector(x:Int, y:Int)
                function +(a:Vector, b:Vector):Int -> a.x + b.x
                assign trait Vector:Numeric { }
                0
                """);
        assertTrue(err.contains("requires operator '+'"), () -> err);
    }

    @Test
    void wrongOperandType_notWitnessed_rejected() {
        // An overload exists under `+`, but not over (Vector, Vector).
        String err = reject("""
                trait Numeric{ +:[Dispatch(this.type, this.type):this.type] }
                struct Vector(x:Int, y:Int)
                struct Other(z:Int)
                function +(a:Other, b:Other):Other -> Other(a.z + b.z)
                assign trait Vector:Numeric { }
                0
                """);
        assertTrue(err.contains("requires operator '+'"), () -> err);
    }

    @Test
    void multipleOperators_oneMissing_rejected() {
        String err = reject("""
                trait Numeric{
                  +:[Dispatch(this.type, this.type):this.type],
                  *:[Dispatch(this.type, this.type):this.type]
                }
                struct Vector(x:Int, y:Int)
                function +(a:Vector, b:Vector):Vector -> Vector(a.x + b.x, a.y + b.y)
                assign trait Vector:Numeric { }
                0
                """);
        assertTrue(err.contains("requires operator '*'"), () -> err);
    }

    @Test
    void multipleOperators_allWitnessed_compiles() {
        // v=(2,3): (v*v).x = 2*2 = 4; (v+v).x = 2+2 = 4; sum = 8.
        assertEquals("8", run("""
                trait Numeric{
                  +:[Dispatch(this.type, this.type):this.type],
                  *:[Dispatch(this.type, this.type):this.type]
                }
                struct Vector(x:Int, y:Int)
                function +(a:Vector, b:Vector):Vector -> Vector(a.x + b.x, a.y + b.y)
                function *(a:Vector, b:Vector):Vector -> Vector(a.x * b.x, a.y * b.y)
                assign trait Vector:Numeric { }
                let v = Vector(2, 3)
                (v * v).x + (v + v).x
                """));
    }

    @Test
    void operatorAndMethodMembers_bothChecked() {
        // The method is implemented in the block; the operator is witnessed by the
        // free overload — both kinds verified in one impl.
        assertEquals("5", run("""
                trait Showy{
                  +:[Dispatch(this.type, this.type):this.type],
                  size:[Method():Int]
                }
                struct Vector(x:Int, y:Int)
                function +(a:Vector, b:Vector):Vector -> Vector(a.x + b.x, a.y + b.y)
                assign trait Vector:Showy {
                  size():Int -> this.x + this.y
                }
                let v = Vector(2, 3)
                v.size()
                """));
    }
}
