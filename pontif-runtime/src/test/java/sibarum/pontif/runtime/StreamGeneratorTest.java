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
 * Stream war slice 2f: the generator / unfold (docs/stream-war.md §7.9) — the
 * <em>dual of fold</em>. A fragment with accumulator inputs and a tuple codomain
 * mixing a {@code Stream[T]} output channel with accumulator channels, applied with
 * <b>no {@code &} input</b>, drives a step-until-the-guard-fails loop. The standout:
 * <b>the domain refinement is the base case</b> — the unfold halts exactly when the
 * next state would make a parameter ill-typed.
 */
class StreamGeneratorTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = AltParser.parseModule(src, "m.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        CompiledModule compiled = new IrCompiler(simp).compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    /** Runs through the full editor pipeline (link + compile), like the playground. */
    private Object runLinked(String src) {
        CompileResult r = compiler.compileAlt(src, "streams.ptf");
        CompileResult.Compiled c = assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "should compile through the editor path; got " + r);
        return new IrInterpreter(c.program().simplifier()).eval(c.program().module());
    }

    @Test
    void count_emitsRangeUntilDomainRefinementFails() throws Exception {
        // The canonical §7.9 generator: `to:[Int:@>=from]` is the base case — the
        // unfold halts when `from` overruns `to`. count(0,5) emits 0..5 then stops.
        assertEquals("(0, 1, 2, 3, 4, 5)", String.valueOf(run("""
                let count:[
                  (from:[Int:@>=0], to:[Int:@>=from]):(Stream[Int], Int, Int) ->
                  (from, from+1, to)
                ]
                count(0, 5)._0""")));
    }

    @Test
    void count_finalAccumulatorsAreTheHaltState() throws Exception {
        // The accumulator channels project the state the unfold halted in: from has
        // stepped one past `to` (the state that tripped the guard), to is unchanged.
        assertEquals(6L, run("""
                let count:[
                  (from:[Int:@>=0], to:[Int:@>=from]):(Stream[Int], Int, Int) ->
                  (from, from+1, to)
                ]
                count(0, 5)._1"""));
    }

    @Test
    void count_emptyWhenGuardFailsImmediately() throws Exception {
        // If the seed state already violates the domain refinement, zero steps run —
        // the stream channel seals empty (no loss, no fabrication).
        assertEquals("()", String.valueOf(run("""
                let count:[
                  (from:[Int:@>=0], to:[Int:@>=from]):(Stream[Int], Int, Int) ->
                  (from, from+1, to)
                ]
                count(5, 0)._0""")));
    }

    @Test
    void generator_firesThroughLinkedEditorPath_withRequires() {
        // REGRESSION: the playground links `requires pontif.core.{Stream}`, which
        // resolves the codomain's Stream channel to the QUALIFIED trait name
        // `pontif.core/Stream`. The generator detector must match the qualified name
        // (not just bare "Stream"), else the unfold never fires and `count` collapses
        // to a single body evaluation `(0, 1, 10)`. Mirrors examples/streams.ptf.
        Object val = runLinked("""
                module examples.stream
                requires pontif.core.{Stream, Nothing}
                let null:Nothing = Nothing()
                let count:[
                  (from:[Int:@>=0], to:[Int:@>=from]):(Stream[Int], Int, Int) ->
                  (from, from+1, to)
                ]
                count(0, 10)._0""");
        assertEquals("(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)", String.valueOf(val));
    }

    @Test
    void countDown_arbitraryStepProvesItIsNotJustIncrement() throws Exception {
        // A descending generator: the guard `from:[Int:@>=0]` is the base case, so it
        // halts when `from` would go negative. Proves the driver isn't hard-wired to
        // increment — any state transition halting on a refinement works.
        assertEquals("(5, 4, 3, 2, 1, 0)", String.valueOf(run("""
                let countDown:[
                  (from:[Int:@>=0]):(Stream[Int], Int) ->
                  (from, from - 1)
                ]
                countDown(5)._0""")));
    }
}
