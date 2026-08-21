package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Method-call resolution is order-independent now that it lives in
 * {@code MethodResolver} (an IR pass), not the parser: a method may be called
 * above its declaration, recurse through {@code this.m()}, and mutually recur
 * with another method. Regression suite for that move.
 */
class MethodResolutionTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "method.ptf");
        PontifCompiler.CompileResult.Compiled c = assertInstanceOf(
                PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        return runner.run(r, PontifRunner.Engine.INTERPRETER).text();
    }

    private PontifCompiler.CompileResult.Failed reject(String src) {
        return assertInstanceOf(PontifCompiler.CompileResult.Failed.class,
                compiler.compile(src, "method.ptf"), "expected a compile rejection");
    }

    @Test
    void methodUsedAboveItsDeclaration_resolves() {
        // `half` is defined BELOW `f`, which calls it. Old parser-time routing
        // rejected this with "Unknown function 'v.half'".
        assertEquals("21", run("""
                struct Box(n:Int)
                function f(b:Box):Int -> b.half().n
                method Box.half():Box -> Box(this.n / 2)
                f(Box(42))
                """));
    }

    @Test
    void selfRecursiveMethod_viaThis_resolves() {
        // A method calling itself through `this.m()` — impossible before, since
        // its own name registered only after its body parsed.
        assertEquals("0", run("""
                struct Countdown(n:Int)
                method Countdown.toZero():Int ->
                  let this.{n}
                  match n {
                    [@<=0] -> n
                    [@>0]  -> Countdown(n - 1).toZero()
                  }
                Countdown(7).toZero()
                """));
    }

    @Test
    void mutuallyRecursiveMethods_resolve() {
        // ping/pong call each other; whichever is second was a forward ref.
        assertEquals("1", run("""
                struct N(v:Int)
                method N.ping():Int ->
                  let this.{v}
                  match v {
                    [@<=0] -> 1
                    [@>0]  -> N(v - 1).pong()
                  }
                method N.pong():Int ->
                  let this.{v}
                  match v {
                    [@<=0] -> 0
                    [@>0]  -> N(v - 1).ping()
                  }
                N(4).ping()
                """));
    }

    @Test
    void loginvolutionPattern_operatorBodyCallsMethodDeclaredBelow() {
        // The original report case: the `/` operator body is `t1 * t2.inv()`,
        // with `inv` declared BELOW it. Compiling at all proves the forward
        // reference resolves; the `*` evaluation pins a scale-stable result.
        assertEquals("Traction{n: 2.0, zexp: 4.0}", run("""
                struct Traction(n:Decimal, zexp:Decimal)
                function *(
                    t1:[Traction.{n->n1, zexp->zexp1}],
                    t2:[Traction.{n->n2, zexp->zexp2}]
                  ):Traction -> Traction(n1*n2, zexp1+zexp2)
                function /(t1:Traction, t2:Traction):Traction -> t1 * t2.inv()
                method Traction.inv():Traction ->
                  let this.{n, zexp}
                  Traction(1.0/n, -zexp)
                Traction(1,1) * Traction(2,3)
                """));
    }

    @Test
    void noSuchMethod_isRejectedWithTypeName() {
        PontifCompiler.CompileResult.Failed f = reject("""
                struct Point(x:Int, y:Int)
                function f(p:Point):Int -> p.nope()
                f(Point(1, 2))
                """);
        String err = f.error().text();
        assertTrue(err.contains("No method 'nope'") && err.contains("Point"), () -> err);
    }

    @Test
    void localDemotedBinding_doesNotLeakConcreteOnlyMethod() {
        // The view leak (roadmap §6.6): a LOCAL demoted `let flat:Point = <Point3D value>` is a
        // VIEW that restricts static access to the declared `Point` interface (docs/type-records.md
        // §"Declared Sort"; roadmap §6.5). Method dispatch must route on the DECLARED claim, so a
        // `Point3D`-only method (`depth`) is NOT reachable through `flat` — even though the value's
        // concrete type is `Point3D`. Before the fix, nominalReceiverSort read the local binding's
        // Inferred head (`Point3D`) and this compiled, exposing `depth`.
        PontifCompiler.CompileResult.Failed f = reject("""
                struct Point(x:Int, y:Int)
                struct Point3D:[Point:@.x==x & @.y==y](x:Int, y:Int, z:Int)
                method Point3D.depth():Int -> this.z
                function f():Int ->
                  let flat:Point = Point3D(2, 3, 5)
                  flat.depth()
                f()
                """);
        String err = f.error().text();
        assertTrue(err.contains("No method 'depth'") && err.contains("'Point'"), () -> err);
    }

    @Test
    void topLevelDemotedBinding_doesNotLeakConcreteOnlyMethod() {
        // Invariant guard: a top-level demoted `let` already routes on the declared sort (its
        // binding sort is narrowed to `Point` at parse time), so the concrete-only `depth` is
        // unreachable through `flat` here too.
        PontifCompiler.CompileResult.Failed f = reject("""
                struct Point(x:Int, y:Int)
                struct Point3D:[Point:@.x==x & @.y==y](x:Int, y:Int, z:Int)
                method Point3D.depth():Int -> this.z
                let p = Point3D(2, 3, 5)
                let flat:Point = p
                flat.depth()
                """);
        String err = f.error().text();
        assertTrue(err.contains("No method 'depth'") && err.contains("'Point'"), () -> err);
    }

    @Test
    void localDeclaredAlias_stillResolvesMethod() {
        // The dual of the leak test — the view restricts, it must not OVER-restrict. A local
        // `let v:Point = {…}` takes its nominal identity from its declared claim `Point`, so a
        // method that IS on `Point` resolves through `v`. (Declared-first for locals.)
        assertEquals("3", run("""
                struct Point(x:Int, y:Int)
                method Point.sum():Int -> this.x + this.y
                function f():Int ->
                  let v:Point = {x=1, y=2}
                  v.sum()
                f()
                """));
    }

    @Test
    void topLevelDeclaredAlias_stillResolvesMethod() {
        // Methods-on-aliases at the top level must keep resolving: `v`'s declared sort `Point`
        // provides the method even though its value is an anonymous aggregate.
        assertEquals("3", run("""
                struct Point(x:Int, y:Int)
                method Point.sum():Int -> this.x + this.y
                let v:Point = {x=1, y=2}
                v.sum()
                """));
    }
}
