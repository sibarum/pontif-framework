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
        PontifCompiler.CompileResult r = compiler.compileAlt(src, "method.ptf");
        PontifCompiler.CompileResult.Compiled c = assertInstanceOf(
                PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        return runner.run(r, PontifRunner.Engine.INTERPRETER).text();
    }

    private PontifCompiler.CompileResult.Failed reject(String src) {
        return assertInstanceOf(PontifCompiler.CompileResult.Failed.class,
                compiler.compileAlt(src, "method.ptf"), "expected a compile rejection");
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
}
