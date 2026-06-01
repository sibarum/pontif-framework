package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-source proof-authoring surface: a {@code proof f = <Leaf/Split tree>}
 * declaration lets the return-refinement gate discharge a return the engine
 * can't prove on its own. The headline: {@code x*(x-1) >= 0} (engine can't —
 * opaque product, sign TOP) closes with a single case-split written in Pontif.
 */
class ProofAuthoringTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private CompileResult compile(String src) {
        return compiler.compileAlt(src, "proof.ptf");
    }

    private void assertCompiles(String src) {
        CompileResult r = compile(src);
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "expected compile success; got: "
                        + ((CompileResult.Failed) r).error().text());
    }

    private String assertRejected(String src) {
        CompileResult r = compile(src);
        assertInstanceOf(CompileResult.Failed.class, r, "expected compile failure");
        return ((CompileResult.Failed) r).error().text();
    }

    /** Declares the Leaf/Split proof structs (recursive type — landed). */
    private static final String STRUCTS = """
            struct Leaf()
            struct Split(p:Bool, whenTrue:[Leaf|Split], whenFalse:[Leaf|Split])
            """;

    @Test
    void hardReturn_withSingleSplitProof_compiles() {
        // x*(x-1) >= 0 for all integers: the engine can't (opaque product), but
        // splitting on x >= 1 closes both sides (both factors same sign).
        assertCompiles(STRUCTS + """
                function f(x:Int):[Int:@>=0] -> x*(x-1)
                proof f = Split(x>=1, Leaf(), Leaf())
                42
                """);
    }

    @Test
    void hardReturn_withoutProof_rejects() {
        String err = assertRejected("""
                function f(x:Int):[Int:@>=0] -> x*(x-1)
                42
                """);
        assertTrue(err.contains("Cannot prove the declared return refinement of 'f'"),
                () -> "expected the no-proof rejection; got: " + err);
    }

    @Test
    void insufficientProof_isStaleHardError() {
        // A bare Leaf supplies no split, so the engine still can't close it.
        String err = assertRejected(STRUCTS + """
                function f(x:Int):[Int:@>=0] -> x*(x-1)
                proof f = Leaf()
                42
                """);
        assertTrue(err.contains("supplied proof for 'f'")
                        && err.contains("stale or insufficient"),
                () -> "expected the stale/insufficient error; got: " + err);
    }

    @Test
    void proofForUnknownFunction_isHardError() {
        String err = assertRejected(STRUCTS + """
                function f(x:Int):Int -> x
                proof ghost = Leaf()
                42
                """);
        assertTrue(err.contains("unknown function 'ghost'"),
                () -> "expected unknown-function error; got: " + err);
    }

    @Test
    void orphanedProof_returnNotRefined_isHardError() {
        // h has no refined return — there's nothing to prove, so the proof is
        // orphaned (e.g. left behind after the narrowing was dropped).
        String err = assertRejected(STRUCTS + """
                function h(x:Int):Int -> x
                proof h = Split(x>=1, Leaf(), Leaf())
                42
                """);
        assertTrue(err.contains("orphaned") && err.contains("'h'"),
                () -> "expected orphaned-proof error; got: " + err);
    }

    @Test
    void duplicateProof_isHardError() {
        String err = assertRejected(STRUCTS + """
                function f(x:Int):[Int:@>=0] -> x*(x-1)
                proof f = Split(x>=1, Leaf(), Leaf())
                proof f = Leaf()
                42
                """);
        assertTrue(err.contains("Duplicate proof for 'f'"),
                () -> "expected duplicate-proof error; got: " + err);
    }

    @Test
    void isSparse_closesPiecewiseViaAuthoredProof() {
        // The flagship hard return: (x-3)*(x+5) >= -16 for all integers (true
        // min -16 at x=-1). Engine can't (opaque product). Proof: split A [x>=3]
        // and C [x<=-6] (interval mult), region B [-5..2] peeled to singletons.
        assertCompiles(STRUCTS + """
                function isSparse(x:Int):[Int:@>=-16] -> (x-3)*(x+5)
                proof isSparse =
                  Split(x>=3, Leaf(),
                    Split(x<=-6, Leaf(),
                      Split(x<=-5, Leaf(),
                        Split(x<=-4, Leaf(),
                          Split(x<=-3, Leaf(),
                            Split(x<=-2, Leaf(),
                              Split(x<=-1, Leaf(),
                                Split(x<=0, Leaf(),
                                  Split(x<=1, Leaf(), Leaf())))))))))
                42
                """);
    }

    @Test
    void unrelatedFunctionEdit_leavesValidProofValid() {
        // "Stale only on meaningful change": a second, unrelated function in the
        // same module doesn't disturb f's node, so f's proof still validates.
        assertCompiles(STRUCTS + """
                function f(x:Int):[Int:@>=0] -> x*(x-1)
                proof f = Split(x>=1, Leaf(), Leaf())
                function unrelated(y:Int):Int -> y + 1
                42
                """);
    }
}
