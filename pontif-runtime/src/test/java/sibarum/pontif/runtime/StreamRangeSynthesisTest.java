package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finite generator synthesis (docs/stream-war.md §7): a
 * {@code Stream[Int:LO <= @ < HI]} sort, requested with the {@code ;} synthesis
 * directive, materializes its bounded discrete range. The membership refinement
 * IS the definition — bounds give a contiguous integer interval, the comparison
 * chain gives the traversal direction, and any non-bound conjunct is a
 * per-element filter evaluated at synthesis time.
 *
 * <p>Rests on two prerequisites landed in the same slice: chained comparisons
 * desugar to conjunctions ({@code 0 <= @ < 10} ≡ {@code 0<=@ & @<10}, which
 * before this slice mis-evaluated as {@code (0<=@) < 10}), and the bare-refined
 * type-arg shorthand ({@code Stream[Int:pred]} ≡ {@code Stream[[Int:pred]]}).
 */
class StreamRangeSynthesisTest {

    private static final String PRELUDE =
            "module examples.stream\nrequires pontif.core.{Stream}\n";

    private String synth(String declared) {
        String src = PRELUDE + "let r:" + declared + ";\nr";
        PontifRunner.RunResult run = new PontifRunner().run(
                new PontifCompiler().compile(src, "range.ptf"),
                PontifRunner.Engine.INTERPRETER);
        assertTrue(!run.isError(), () -> "expected synthesis to run; got " + run.text());
        return run.text();
    }

    private String error(String declared) {
        String src = PRELUDE + "let r:" + declared + ";\nr";
        PontifRunner.RunResult run = new PontifRunner().run(
                new PontifCompiler().compile(src, "range.ptf"),
                PontifRunner.Engine.INTERPRETER);
        assertTrue(run.isError(), () -> "expected an error; got " + run.text());
        return run.text();
    }

    // --- direction ---------------------------------------------------------

    @Test
    void ascending_halfOpen() {
        // `0 <= @ < 10` — lower bound written first ⇒ ascending; upper exclusive.
        assertEquals("{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}", synth("Stream[Int:0 <= @ < 10]"));
    }

    @Test
    void descending_halfOpen() {
        // `10 > @ >= 0` — upper bound written first ⇒ descending. The direction
        // is read from the comparison chain, not hard-wired.
        assertEquals("{9, 8, 7, 6, 5, 4, 3, 2, 1, 0}", synth("Stream[Int:10 > @ >= 0]"));
    }

    @Test
    void inclusiveUpper() {
        assertEquals("{1, 2, 3, 4, 5}", synth("Stream[Int:1 <= @ <= 5]"));
    }

    @Test
    void conjunctionForm_equalsChain() {
        // The non-chained spelling `@>=0 & @<5` is the same interval as `0<=@<5`.
        assertEquals("{0, 1, 2, 3, 4}", synth("Stream[Int:@>=0 & @<5]"));
    }

    // --- edges -------------------------------------------------------------

    @Test
    void emptyRange_sealsEmpty() {
        // No element satisfies `5 <= @ < 5` — the stream seals empty, no
        // fabrication (the empty tuple).
        assertEquals("{}", synth("Stream[Int:5 <= @ < 5]"));
    }

    @Test
    void singleton() {
        assertEquals("{3}", synth("Stream[Int:3 <= @ <= 3]"));
    }

    @Test
    void unbounded_isHonestlyRejected() {
        // One-sided bound is not finitely materializable — an honest
        // not-synthesizable error, not a hang or a silent empty.
        assertTrue(error("Stream[Int:@>=0]").contains("does not pin a synthesizable value"));
    }

    // --- conditions alongside the range ------------------------------------

    @Test
    void filter_inequality() {
        assertEquals("{0, 1, 2, 3, 4, 6, 7, 8, 9}", synth("Stream[Int:0 <= @ < 10 & @ != 5]"));
    }

    @Test
    void filter_multipleExclusions() {
        assertEquals("{0, 1, 2, 4, 5, 6, 8, 9}",
                synth("Stream[Int:0 <= @ < 10 & @ != 3 & @ != 7]"));
    }

    @Test
    void filter_disjunction() {
        assertEquals("{2, 8}", synth("Stream[Int:0 <= @ < 10 & (@ == 2 | @ == 8)]"));
    }

    @Test
    void filter_linearArithmetic() {
        assertEquals("{0, 1, 2, 3}", synth("Stream[Int:0 <= @ < 10 & @ * 2 < 8]"));
    }

    @Test
    void extraOrderComparison_foldsIntoBound() {
        // `@ > 2` is recognized as a bound and tightens the interval (lo→3),
        // rather than running as a per-element filter.
        assertEquals("{3, 4, 5, 6, 7, 8, 9}", synth("Stream[Int:0 <= @ < 10 & @ > 2]"));
    }

    // --- the chained-comparison desugaring this slice also fixes ----------

    @Test
    void chainedComparison_inOrdinaryRefinement_nowSoundOnValues() {
        // REGRESSION: before chain desugaring, `0 <= @ < 10` parsed as
        // `(0<=@) < 10` and even an in-range value was rejected. Now the
        // in-range value binds and an out-of-range one is rejected.
        String ok = PRELUDE + "let x:[Int:0 <= @ < 10] = 5;\nx";
        PontifRunner.RunResult pass = new PontifRunner().run(
                new PontifCompiler().compile(ok, "chain.ptf"), PontifRunner.Engine.INTERPRETER);
        assertTrue(!pass.isError(), () -> "in-range value should bind; got " + pass.text());
        assertEquals("5", pass.text());

        String bad = PRELUDE + "let x:[Int:0 <= @ < 10] = 50;\nx";
        PontifRunner.RunResult fail = new PontifRunner().run(
                new PontifCompiler().compile(bad, "chain.ptf"), PontifRunner.Engine.INTERPRETER);
        assertTrue(fail.isError(), () -> "out-of-range value should be rejected; got " + fail.text());
    }
}
