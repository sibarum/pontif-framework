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
     * The gate-sensitive return shapes from the playground sample that the
     * engine CAN discharge: inductive ({@code factorial}), linear-bound
     * ({@code inc}), bare ({@code sign}), value-pinned spec-only
     * ({@code timesTwo}). Runs cleanly and evaluates.
     *
     * <p>(Excludes {@code isEven}/{@code isOdd} — see
     * {@link #unionMutualRecursion_returnNotYetGateProvable}.)
     */
    @Test
    void playgroundSample_dischargeableReturns_runsEndToEnd() {
        String src = """
                module tour

                function factorial(n:[Int:@==0]) :[Int:@>=1] -> 1
                function factorial(n:[Int:@>0])  :[Int:@>=1] -> n * factorial(n-1)

                function inc(x:[Int:@>=1]):[Int:@>1] -> x + 1

                function sign(n:Int):Int -> match n
                  [@<0 ] -> -1
                  [@==0] ->  0
                  [@>0 ] ->  1

                function timesTwo(n:Int):[Int:n*2]

                factorial(5) + inc(4) + sign(-3) + timesTwo(7)
                """;
        RunResult r = run(src);
        assertFalse(r.isError(), () -> "must run cleanly; got: " + r.text());
        assertEquals("138", r.text());  // 120 + 5 + (-1) + 14
    }

    /**
     * KNOWN GAP (pre-existing, surfaced 2026-06-01): the playground's shipped
     * {@code App.DEFAULT_CODE} includes {@code isEven}/{@code isOdd} with a union
     * return {@code [Int:0|1]} over mutual recursion — and it does NOT pass the
     * return gate, so the default sample errors on the first Run. Closing it
     * needs {@code r_0 == r_1 ∧ r_1 ∈ {0,1} ⟹ r_0 ∈ {0,1}}: reasoning from a
     * disjunctive <em>hypothesis</em> (the call's union-narrowed result), which
     * the Or-<em>goal</em> discharge doesn't cover, and which can't be proof-
     * authored yet (overloaded functions are out of v1 proof scope). Not caused
     * by recursive types / proof-authoring — the discharge path is unchanged.
     * Flip when the engine (or the default sample) is fixed.
     */
    @Test
    void unionMutualRecursion_returnNotYetGateProvable() {
        String src = """
                function isEven(n:[Int:@==0]) :[Int:0|1] -> 1
                function isEven(n:[Int:@>0])  :[Int:0|1] -> isOdd(n-1)
                function isOdd (n:[Int:@==0]) :[Int:0|1] -> 0
                function isOdd (n:[Int:@>0])  :[Int:0|1] -> isEven(n-1)
                isEven(8)
                """;
        RunResult r = run(src);
        assertTrue(r.isError(), "expected the gate to reject the union mutual-recursion return");
        assertTrue(r.text().contains("isEven") || r.text().contains("isOdd"),
                () -> "expected an isEven/isOdd return-gate rejection; got: " + r.text());
    }

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
