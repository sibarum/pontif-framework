package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The synthesis/membership unification (war/unify-synthesis-prover). A refinement
 * {@code [Int:P]} denotes a <em>set</em>; the SAME predicate serves two consumers:
 *
 * <ul>
 *   <li><b>membership</b> — a parameter/return guard {@code x:[Int:P]} <em>tests</em>
 *       an incoming value (the prover's {@code Refinements.satisfies}); and
 *   <li><b>enumeration</b> — {@code Stream[Int:P]} (with the {@code ;} directive)
 *       <em>produces</em> the set's members, by filtering the {@code BoundAnalysis}
 *       domain through that very same {@code satisfies}.
 * </ul>
 *
 * There is no parser-side predicate evaluator any more: both paths run through the
 * one prover ({@code Synthesis} → {@code BoundAnalysis} + {@code Refinements.satisfies}).
 * A pinned witness is the size-1 case of the same enumeration.
 */
class SynthesisUnificationTest {

    private static final String PRELUDE =
            "module examples.synth\nrequires pontif.core.{Stream}\n";

    private PontifRunner.RunResult run(String src) {
        return new PontifRunner().run(
                new PontifCompiler().compile(src, "synth.ptf"), PontifRunner.Engine.INTERPRETER);
    }

    private String value(String src) {
        PontifRunner.RunResult r = run(src);
        assertTrue(!r.isError(), () -> "expected success; got " + r.text());
        return r.text();
    }

    // --- James's exact examples (the ruling) -------------------------------

    @Test
    void streamSynthesis_ascending_fromLowerBoundFirst() {
        // let myStream:[Stream[Int:@>2 & @<10]];  # -> {3,4,5,…}
        assertEquals("{3, 4, 5, 6, 7, 8, 9}",
                value(PRELUDE + "let myStream:[Stream[Int:@>2 & @<10]];\nmyStream"));
    }

    @Test
    void streamSynthesis_descending_fromUpperBoundFirst() {
        // let myStream:[Stream[Int:@<10 & @>2]];  # -> {9,8,7,…}
        assertEquals("{9, 8, 7, 6, 5, 4, 3}",
                value(PRELUDE + "let myStream:[Stream[Int:@<10 & @>2]];\nmyStream"));
    }

    // --- the unification: one predicate, both consumers --------------------

    @Test
    void samePredicate_guardsAndSynthesizes() {
        // The refinement `[Int:0 <= @ < 10 & @ != 5]` is used BOTH as a parameter
        // guard (membership) and as a stream's element type (enumeration). The guard
        // accepts exactly the members the synthesis enumerates — one predicate.
        String guardAccepts = PRELUDE
                + "function ok(x:[Int:0 <= @ < 10 & @ != 5]):[Int] -> x\n"
                + "ok(7)";
        assertEquals("7", value(guardAccepts));

        String synthesized = PRELUDE
                + "let s:[Stream[Int:0 <= @ < 10 & @ != 5]];\ns";
        assertEquals("{0, 1, 2, 3, 4, 6, 7, 8, 9}", value(synthesized));
    }

    @Test
    void guardRejectsTheExcludedMember() {
        // 5 is excluded by the predicate, so the guard rejects it — the value that
        // the synthesis correspondingly drops.
        PontifRunner.RunResult r = run(PRELUDE
                + "function ok(x:[Int:0 <= @ < 10 & @ != 5]):[Int] -> x\n"
                + "ok(5)");
        assertTrue(r.isError(), () -> "5 is out of the set; the guard must reject it; got " + r.text());
    }

    // --- pinned witness = the size-1 case of the same routine --------------

    @Test
    void scalarPin_isTheSingletonExtension() {
        // A scalar refinement whose extension is a single integer pins that value —
        // the same enumerate-and-filter, expecting exactly one survivor.
        assertEquals("0", value(PRELUDE + "let z:[Int:@ > -1 & @ < 1];\nz"));
    }
}
