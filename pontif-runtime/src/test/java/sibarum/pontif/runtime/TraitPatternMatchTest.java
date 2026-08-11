package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bare NOMINAL TRAIT used as a {@code match} arm pattern ({@code [Trait] -> …})
 * must test real trait satisfaction against the value's concrete type. Previously
 * the arm matched EVERY value (Refinements is deliberately lenient on bare
 * non-primitive names, deferring trait enforcement to dispatch — but a match arm
 * has no dispatch gate), so a non-satisfier fell into the trait arm and then died
 * with a baffling "No method 'Trait.m' is declared" the moment the arm called the
 * method. These pin the corrected routing.
 */
class TraitPatternMatchTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        CompileResult r = compiler.compileAlt(src, "traitmatch.ptf");
        CompileResult.Compiled c = assertInstanceOf(
                CompileResult.Compiled.class, r, () -> "expected compile success; got " + r);
        PontifRunner.RunResult rr = runner.run(c.program(), Engine.INTERPRETER);
        assertTrue(!rr.isError(), () -> "run error: " + rr.text());
        return rr.text();
    }

    @Test void nonSatisfier_routesToDefaultArm() {
        assertEquals("\"other\"", run("""
                trait Named{ nm:[Method():String] }
                struct A(v:Int)
                struct B(v:Int)
                assign trait A:Named { nm():String -> "a" }
                function label(x:_):String -> match x {
                  [Named] -> x.nm()
                  [_] -> "other"
                }
                label(B(3))"""));
    }

    @Test void satisfier_routesIntoTraitArm() {
        assertEquals("\"a\"", run("""
                trait Named{ nm:[Method():String] }
                struct A(v:Int)
                assign trait A:Named { nm():String -> "a" }
                function label(x:_):String -> match x {
                  [Named] -> x.nm()
                  [_] -> "other"
                }
                label(A(3))"""));
    }

    /** The trait method dispatches to the impl of the value's concrete type. */
    @Test void trait_dispatch_picks_concrete_impl_across_arms() {
        assertEquals("\"a|other|b\"", run("""
                trait Named{ nm:[Method():String] }
                struct A(v:Int)
                struct B(v:Int)
                struct C(v:Int)
                assign trait A:Named { nm():String -> "a" }
                assign trait B:Named { nm():String -> "b" }
                function label(x:_):String -> match x {
                  [Named] -> x.nm()
                  [_] -> "other"
                }
                label(A(1)) + "|" + label(C(2)) + "|" + label(B(3))"""));
    }
}
