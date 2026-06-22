package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.AltParser;
import sibarum.pontif.parser.ParseException;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Stream war slice 2c: the <em>fragment literal</em> {@code let f:[ (el:Int) ->
 * body ]} — the synthesis fragment as a first-class value (docs/stream-war.md §3,
 * James's ruling). A fragment is a closure (the lambda replacement): bound by
 * {@code let}, applied directly or by {@code &} spread.
 */
class StreamFragmentTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = AltParser.parseModule(src, "m.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        CompiledModule compiled = new IrCompiler(simp).compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    @Test
    void fragment_directApplication() throws Exception {
        // A bound fragment is a callable value.
        assertEquals(6L, run("""
                let double:[ (el:Int) -> el * 2 ]
                double(3)"""));
    }

    @Test
    void fragment_spreadIsMap() throws Exception {
        // `&` spreads the fragment over a stream — map, no ceremony.
        assertEquals("(2, 4, 6, 8)", String.valueOf(run("""
                let double:[ (el:Int) -> el * 2 ]
                let s = (1, 2, 3, 4)
                double(&s)""")));
    }

    @Test
    void spreadAscription_inlineMap() throws Exception {
        // The inline/anonymous face: `&s:[ (el)-> … ]` ≡ applying the transform per
        // element. No name needed.
        assertEquals("(2, 4, 6, 8)", String.valueOf(run("""
                let s = (1, 2, 3, 4)
                &s:[ (el:Int) -> el * 2 ]""")));
    }

    @Test
    void spreadAscription_inlineFilter_dropsNothing() {
        // The canonical filteredLossy line in the ruled syntax: &s:[ fragment ].
        CompileResult r = compiler.compileAlt("""
                requires pontif.core.{Stream, Nothing}
                let null:Nothing = Nothing()
                let s:Stream[Int] = (1, 2, 3, 4)
                &s:[
                  (el:Int) -> match el {
                    [@>2] -> el
                    [_]   -> null
                  }
                ]""", "m.ptf");
        CompileResult.Compiled c = assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "the inline ascription filter should compile; got " + r);
        Object val = new IrInterpreter(c.program().simplifier()).eval(c.program().module());
        assertEquals("(3, 4)", String.valueOf(val));
    }

    @Test
    void fold_accumulatesViaSeedArg() {
        // A value-arg alongside the spread is an accumulator seed (total-input-marker
        // rule). The fragment returns (streamPos, accPos); fold sends null to the
        // stream (empty) and threads the running total. Tuples are destructure-only,
        // so the result is bound positionally — `total` is the final accumulator.
        CompileResult r = compiler.compileAlt("""
                requires pontif.core.{Stream, Nothing}
                let null:Nothing = Nothing()
                let fold:[ (el:Int, total:Int) -> (null, el + total) ]
                let s:Stream[Int] = (1, 2, 3, 4)
                fold(&s, 0)._1""", "m.ptf");
        CompileResult.Compiled c = assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "fold should compile; got " + r);
        Object val = new IrInterpreter(c.program().simplifier()).eval(c.program().module());
        assertEquals(10L, val);
    }

    @Test
    void scan_emitsRunningTotalAndThreads() throws Exception {
        // scan emits the running accumulator at the stream channel AND threads it:
        // the stream position is the running totals, the accumulator the final.
        assertEquals("(1, 3, 6, 10)", String.valueOf(run("""
                let scan:[ (el:Int, total:Int) -> (el + total, el + total) ]
                let s = (1, 2, 3, 4)
                let [(running, final)] = scan(&s, 0)
                running""")));
        assertEquals(10L, run("""
                let scan:[ (el:Int, total:Int) -> (el + total, el + total) ]
                let s = (1, 2, 3, 4)
                let [(running, final)] = scan(&s, 0)
                final"""));
    }

    @Test
    void fork_fansOutToTwoStreams() {
        // A tuple codomain of stream channels is fan-out (fork): each element is
        // routed to exactly one of two output streams (null drops the other) — the
        // conservative split. The codomain distinguishes this from a stream-of-tuples.
        CompileResult r = compiler.compileAlt("""
                requires pontif.core.{Stream, Nothing}
                let null:Nothing = Nothing()
                let s:Stream[Int] = (1, 2, 3, 4)
                &s:[
                  (el:Int):(Stream[Int], Stream[Int]) -> match el {
                    [@>2] -> (el, null)
                    [_]   -> (null, el)
                  }
                ]""", "m.ptf");
        CompileResult.Compiled c = assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "fork should compile; got " + r);
        Object val = new IrInterpreter(c.program().simplifier()).eval(c.program().module());
        // _0 collects the >2 elements, _1 the rest — no loss, no duplication.
        assertEquals("((3, 4), (1, 2))", String.valueOf(val));
    }

    @Test
    void mapReturningTuple_isStreamOfTuples_notFork() throws Exception {
        // The dual: a tuple body with NO codomain is a plain map producing a stream
        // of pairs — proving the codomain is what selects fan-out.
        assertEquals("((1, 2), (2, 4), (3, 6))", String.valueOf(run("""
                let s = (1, 2, 3)
                &s:[ (el:Int) -> (el, el * 2) ]""")));
    }

    @Test
    void zip_walksTwoStreamsInLockstep() throws Exception {
        // (&a, &b) walks both in lockstep; the element binds to the pair, which the
        // fragment destructures into (x, y). Vector-add: x + y per position.
        assertEquals("(11, 22, 33)", String.valueOf(run("""
                let a = (1, 2, 3)
                let b = (10, 20, 30)
                (&a, &b):[ (x:Int, y:Int) -> x + y ]""")));
    }

    @Test
    void zip_raggedStopsAtShortest() throws Exception {
        // Unequal lengths stop at the shortest stream (standard zip).
        assertEquals("(11, 22)", String.valueOf(run("""
                let a = (1, 2, 3, 4)
                let b = (10, 20)
                (&a, &b):[ (x:Int, y:Int) -> x + y ]""")));
    }

    @Test
    void fragment_filter_dropsNothing() {
        // The canonical filteredLossy line: a fragment whose arms return the
        // element or null; spread over the stream, the nulls drop.
        CompileResult r = compiler.compileAlt("""
                requires pontif.core.{Stream, Nothing}
                let null:Nothing = Nothing()
                let filter:[
                  (el:Int) -> match el {
                    [@>2] -> el
                    [_]   -> null
                  }
                ]
                let s:Stream[Int] = (1, 2, 3, 4)
                filter(&s)""", "m.ptf");
        CompileResult.Compiled c = assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "the fragment filter should compile; got " + r);
        Object val = new IrInterpreter(c.program().simplifier()).eval(c.program().module());
        assertEquals("(3, 4)", String.valueOf(val));
    }
}
