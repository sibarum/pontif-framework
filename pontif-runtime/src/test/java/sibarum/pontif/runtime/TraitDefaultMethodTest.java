package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for DEFAULT trait method implementations.
 *
 * <p>A trait may give a method a body in its body using the impl-method form
 * ({@code quack():Int -> this.x}); an {@code assign trait} block that omits the
 * method inherits the default, one that provides it overrides. Lowered by
 * {@code TraitDefaultExpansion}, which clones the default into a per-impl
 * {@code Type.method} FunctionDecl when the impl doesn't supply one — so the
 * default rides the ordinary dispatch path (full self-reference: it may read
 * {@code this}, call sibling methods, etc.).
 */
class TraitDefaultMethodTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        CompileResult r = compiler.compile(src, "trait-default.ptf");
        CompileResult.Compiled c = assertInstanceOf(
                CompileResult.Compiled.class, r, () -> "expected compile success; got " + r);
        PontifRunner.RunResult rr = runner.run(c.program(), Engine.INTERPRETER);
        assertFalse(rr.isError(), () -> "run error: " + rr.text());
        return rr.text();
    }

    private String reject(String src) {
        CompileResult r = compiler.compile(src, "trait-default.ptf");
        return ((CompileResult.Failed) assertInstanceOf(
                CompileResult.Failed.class, r, "expected a compile rejection")).error().text();
    }

    // 1. Impl omits the method → the trait default runs.
    @Test
    void inheritedDefault_runsWhenImplOmitsMethod() {
        assertEquals("7", run("""
                trait Greeter{ greet():Int -> 7 }
                struct T(x:Int)
                assign trait T:Greeter {
                }
                let t = T(0)
                t.greet()"""));
    }

    // 2. Impl provides the method → the override runs (default ignored).
    @Test
    void implOverridesDefault() {
        assertEquals("99", run("""
                trait Greeter{ greet():Int -> 7 }
                struct T(x:Int)
                assign trait T:Greeter {
                  greet():Int -> 99
                }
                let t = T(0)
                t.greet()"""));
    }

    // 3. The default body reads instance state through `this`.
    @Test
    void defaultReadsThisField() {
        assertEquals("11", run("""
                trait Bumped{ bump():Int -> this.v + 1 }
                struct T(v:Int)
                assign trait T:Bumped {
                }
                let t = T(10)
                t.bump()"""));
    }

    // 4. The default takes a user parameter alongside `this`.
    @Test
    void defaultWithUserParam() {
        assertEquals("15", run("""
                trait Adder{ addTo(n:Int):Int -> this.v + n }
                struct T(v:Int)
                assign trait T:Adder {
                }
                let t = T(10)
                t.addTo(5)"""));
    }

    // 5. The default calls a sibling ABSTRACT method the impl supplies — the
    //    classic "default calls a required method" pattern.
    @Test
    void defaultCallsSiblingAbstractMethod() {
        assertEquals("42", run("""
                trait Doubler{
                  base:[Method():Int],
                  doubled():Int -> this.base() + this.base()
                }
                struct T(x:Int)
                assign trait T:Doubler {
                  base():Int -> 21
                }
                let t = T(0)
                t.doubled()"""));
    }

    // 6. A default calls another DEFAULTED sibling — both synthesized.
    @Test
    void defaultCallsSiblingDefault() {
        assertEquals("2", run("""
                trait Counter{
                  one():Int -> 1,
                  two():Int -> this.one() + this.one()
                }
                struct T(x:Int)
                assign trait T:Counter {
                }
                let t = T(0)
                t.two()"""));
    }

    // 7. A non-defaulted (abstract) method the impl omits is still required.
    @Test
    void abstractMethodStillRequired() {
        String err = reject("""
                trait Mixed{
                  needed:[Method():Int],
                  provided():Int -> 0
                }
                struct T(x:Int)
                assign trait T:Mixed {
                }
                T(0)""");
        assertTrue(err.contains("missing method 'needed'"),
                () -> "abstract member must still be required; got: " + err);
    }

    // 8. A default inherited through a base trait (`trait Derived : Base`).
    @Test
    void baseTraitDefaultInherited() {
        assertEquals("6", run("""
                trait Base{ a():Int -> 5 }
                trait Derived:Base{ b:[Method():Int] }
                struct T(x:Int)
                assign trait T:Derived {
                  b():Int -> 1
                }
                let t = T(0)
                t.a() + t.b()"""));
    }

    // 9. The default resolves through a bare trait-typed parameter (trait-view
    //    dispatch), not just a direct struct receiver.
    @Test
    void defaultThroughTraitTypedParam() {
        assertEquals("7", run("""
                trait Greeter{ greet():Int -> 7 }
                struct T(x:Int)
                assign trait T:Greeter {
                }
                function useGreeter(g:Greeter):Int -> g.greet()
                useGreeter(T(0))"""));
    }

    // 10. A default method whose SIGNATURE names its own (self-referential) trait
    //     must resolve. NameResolver FQN-qualifies contract-method signatures but
    //     once carried the parallel methodDefaults signatures through verbatim, so
    //     the cloned default kept a bare `Expr` while the contract had `mod/Expr`;
    //     the FQN-keyed alias table then failed to resolve the bare name and the
    //     default's return was rejected as an unknown sort — at a trait that IS
    //     declared. Regression guard: the self-typed default synthesizes and runs.
    @Test
    void defaultReturnNamesOwnTrait() {
        assertEquals("Leaf{}", run("""
                trait Expr{ simplify():Expr -> this }
                struct Leaf()
                assign trait Leaf:Expr {
                }
                Leaf().simplify()"""));
    }

    // 11. Same self-reference nested inside a parametric return, exercised through
    //     an inherited default on a sub-struct along a base chain — the shape the
    //     original traction bug report hit (`walk():Stream[this.type]` beside a
    //     `simplify():Expr` default, invoked on an `Exp:BiOp` value).
    @Test
    void selfReferentialDefaultAlongInheritanceChain() {
        assertEquals(
                "{_anonymous/Leaf{}, _anonymous/Exp{left: _anonymous/Leaf{}, "
                        + "right: _anonymous/Leaf{}}, _anonymous/Leaf{}}",
                run("""
                requires pontif.core.{Stream}
                trait Expr{
                  walk():Stream[this.type] -> {this},
                  simplify():Expr -> this
                }
                struct Leaf()
                struct BiOp(left:Expr, right:Expr)
                struct Exp:BiOp(left:Expr, right:Expr)
                assign trait Leaf:Expr {}
                assign trait BiOp:Expr {
                  walk():Stream[Expr] -> this.left.walk() + {this} + this.right.walk()
                }
                let sample:Exp = Exp(Leaf(), Leaf())
                sample.walk()"""));
    }
}
