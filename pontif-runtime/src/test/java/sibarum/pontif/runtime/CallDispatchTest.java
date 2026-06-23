package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Stream war: call-site dispatch — a positional tuple argument matches a {@code Stream[T]}
 * parameter (the §4 autobox, applied in dispatch matching, with elements checked against T
 * per §8.6). This is what lets a stream-taking function be called with a literal — and is
 * the last gap before a CONCRETE map runs end-to-end.
 */
class CallDispatchTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private Object run(String src) {
        CompileResult r = compiler.compileAlt(src, "m.ptf");
        CompileResult.Compiled c = assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "should compile; got " + (r instanceof CompileResult.Failed f ? f.error().text() : r));
        return new IrInterpreter(c.program().simplifier()).eval(c.program().module());
    }

    @Test void tupleArg_matchesStreamParam() {
        assertEquals(3L, run("""
                requires pontif.core.{Stream}
                function len(s:Stream[Int]):Int -> 3
                len({1, 2, 3})"""));
    }

    @Test void tupleArg_matchesGenericStreamParam() {
        assertEquals(0L, run("""
                requires pontif.core.{Stream}
                function len[type A](s:Stream[A]):Int -> 0
                len({1, 2, 3})"""));
    }

    @Test void concreteMap_runsEndToEnd() {
        // The payoff: a stream-mapping function called with a tuple literal + a metaref.
        assertEquals("{2, 4, 6}", String.valueOf(run("""
                requires pontif.core.{Stream}
                function double(x:Int):Int -> x * 2
                function map(s:Stream[Int], d:[Dispatch(Int):Int]):Stream[Int] ->
                  &s:[ (el:Int) -> d(el) ]
                map({1, 2, 3}, $double[Int])""")));
    }

    @Test void wrongElementType_tupleDoesNotMatchStreamParam() {
        // A String tuple must NOT match a Stream[Int] param (the §8.6 element check runs
        // in dispatch too) — so this fails to dispatch rather than silently matching.
        CompileResult r = compiler.compileAlt("""
                requires pontif.core.{Stream}
                function len(s:Stream[Int]):Int -> 3
                len({"a", "b"})""", "m.ptf");
        boolean rejected;
        if (r instanceof CompileResult.Compiled c) {
            try { new IrInterpreter(c.program().simplifier()).eval(c.program().module()); rejected = false; }
            catch (RuntimeException e) { rejected = true; }
        } else rejected = true;
        org.junit.jupiter.api.Assertions.assertTrue(rejected,
                "a String tuple must not match a Stream[Int] parameter");
    }
}
