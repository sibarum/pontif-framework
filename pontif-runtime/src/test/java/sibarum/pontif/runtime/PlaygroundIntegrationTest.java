package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of the path the playground actually drives:
 * {@code App.onRunClicked} → {@code PontifCompiler.compileAlt} (alt parser) →
 * the return-refinement gate → {@code PontifRunner.run(INTERPRETER)}. Catches a
 * regression that would make the playground error on the first click — which
 * unit tests on individual passes can miss.
 */
class PlaygroundIntegrationTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String src) {
        return runner.run(compiler.compileAlt(src, "<editor>"), Engine.INTERPRETER);
    }

    /**
     * The playground's shipped default tour (mirrors {@code App.DEFAULT_CODE}):
     * an auto-discharged linear bound ({@code inc}), recursive proof structs,
     * and a hard return ({@code quirk = x*(x-1) >= 0}) rescued by a hand-written
     * case-split proof. Must compile past the gate and evaluate to 25. If this
     * breaks, the playground errors on the first Run — the canary.
     */
    @Test
    void playgroundDefaultTour_compilesPastGateAndEvaluates() {
        RunResult r = run(DEFAULT_TOUR);
        assertFalse(r.isError(), () -> "playground default must run cleanly; got: " + r.text());
        assertEquals("25", r.text());  // inc(4)=5 + quirk(5)=20
    }

    /** The proof is load-bearing: drop it and the same program is rejected. */
    @Test
    void playgroundDefaultTour_withoutProof_isRejected() {
        String withoutProof = DEFAULT_TOUR.replaceAll("(?m)^proof quirk.*$", "");
        RunResult r = run(withoutProof);
        assertTrue(r.isError(), "without the proof, quirk's [Int:@>=0] must be rejected");
        assertTrue(r.text().contains("quirk"), () -> "expected a quirk rejection; got: " + r.text());
    }

    /**
     * The playground's shipped default ({@code App.DEFAULT_CODE} is the same
     * {@link QuickTour#SOURCE}), so this canary tracks the real default with no
     * hand-kept copy to drift.
     */
    private static final String DEFAULT_TOUR = QuickTour.SOURCE;

    /**
     * A proof-bearing program runs end to end: the gate accepts the hard return
     * via the in-source proof, the {@code proof} declaration is inert at
     * runtime, and the main expression evaluates normally.
     */
    @Test
    void proofBearingProgram_runsEndToEndToAValue() {
        String src = """
                struct Leaf()
                struct Split(p:Bool, whenTrue:[Leaf|Split], whenFalse:[Leaf|Split])
                function f(x:Int):[Int:@>=0] -> x*(x-1)
                proof f = Split(x>=1, Leaf(), Leaf())
                f(3)
                """;
        RunResult r = run(src);
        assertFalse(r.isError(), () -> "proof-bearing program must run; got: " + r.text());
        assertEquals("6", r.text());  // 3*(3-1) = 6
    }

    /** A recursive-type value constructs and traverses end to end via the runner. */
    @Test
    void recursiveTypeProgram_runsEndToEndToAValue() {
        String src = """
                struct Leaf(v:Int)
                struct Pair(a:[Leaf|Pair], b:[Leaf|Pair])
                function sumLeaves(p:Pair):Int -> p.a.v + p.b.v
                sumLeaves(Pair(Leaf(3), Leaf(4)))
                """;
        RunResult r = run(src);
        assertFalse(r.isError(), () -> "recursive-type program must run; got: " + r.text());
        assertEquals("7", r.text());
    }
}
