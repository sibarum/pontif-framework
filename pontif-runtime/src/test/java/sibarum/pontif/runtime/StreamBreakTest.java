package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Stream war: the {@code Break} termination value (docs/stream-war.md §3, RULED
 * 2026-07-04). Stream control is expressed as returned values in the {@code Nothing}
 * family — {@code Nothing} drops one element, {@code Break} HALTS the stream. This is how
 * takeWhile is now spelled (an input refinement is a per-element filter, never
 * stream-ending — see {@code StreamGuardFilterTest}) and how an infinite/unbounded stream
 * is given an early cutoff. The triggering element is not emitted.
 */
class StreamBreakTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private Object run(String src) {
        CompileResult r = compiler.compileAlt(src, "m.ptf");
        CompileResult.Compiled c = assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "should compile; got " + r);
        return new IrInterpreter(c.program().simplifier()).eval(c.program().module());
    }

    @Test void breakHalts_takeWhileShape() {
        // takeWhile, now via a returned Break: emit while in-domain, HALT at the first
        // that isn't (the 9). Contrast the guard-filter, which would drop the 9 and keep
        // the later 3 → {1,2,3}. Break stops → {1,2}.
        assertEquals("{1, 2}", String.valueOf(run("""
                requires pontif.core.{Stream, Break}
                let stop:Break = Break()
                let s:Stream[Int] = {1, 2, 9, 3}
                &s:[
                  (el:Int) -> match el {
                    [@<5] -> el
                    [_]   -> stop
                  }
                ]""")));
    }

    @Test void breakOnFirstElement_emptyStream() {
        assertEquals("{}", String.valueOf(run("""
                requires pontif.core.{Stream, Break}
                let stop:Break = Break()
                let s:Stream[Int] = {9, 1, 2}
                &s:[
                  (el:Int) -> match el {
                    [@<5] -> el
                    [_]   -> stop
                  }
                ]""")));
    }

    @Test void breakNeverFires_wholeStream() {
        assertEquals("{1, 2, 3}", String.valueOf(run("""
                requires pontif.core.{Stream, Break}
                let stop:Break = Break()
                let s:Stream[Int] = {1, 2, 3}
                &s:[
                  (el:Int) -> match el {
                    [@<5] -> el
                    [_]   -> stop
                  }
                ]""")));
    }

    @Test void breakHaltsAMultiChannelFold() {
        // Break in a multi-channel (map+fold) body halts through the FAN write path, so it
        // never leaks into the output stream (only Nothing is a per-element drop). el<3
        // maps to el*10 and folds the sum; at el=3 both channels Break → halt. So the
        // stream is {10,20} and the accumulator is 1+2=3 (el=3 not folded).
        assertEquals("{{10, 20}, 3}", String.valueOf(run("""
                requires pontif.core.{Stream, Break}
                let stop:Break = Break()
                let f:[ (el:Int, total:Int) -> match el {
                    [@<3] -> {el * 10, el + total}
                    [_]   -> {stop, stop}
                  } ]
                let s:Stream[Int] = {1, 2, 3, 4}
                f(&s, 0)""")));
    }

    @Test void breakCutsAnUnboundedGenerator() {
        // The infinite-stream payoff: a generator emits 0,1,2,… and a downstream Break
        // gives it a finite cutoff. count(0) produces an ascending stream; take while <3.
        // (The generator halts by its own producer refinement; here we prove Break is the
        // consumer-side cutoff on the emitted stream.)
        assertEquals("{0, 1, 2}", String.valueOf(run("""
                requires pontif.core.{Stream, Break}
                let stop:Break = Break()
                let count:[ (from:[Int:@>=0], to:[Int:@>=from]):{Stream[Int], Int, Int}
                            -> {from, from + 1, to} ]
                let nums:Stream[Int] = count(0, 100)._0
                &nums:[
                  (el:Int) -> match el {
                    [@<3] -> el
                    [_]   -> stop
                  }
                ]""")));
    }
}
