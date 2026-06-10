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
        // (x-3)*(x+5) >= -16 is true but beyond the engine (the min -16 sits at the
        // interior x=-1, which the sign-chart's root cuts don't isolate), so without
        // a proof the declared return is rejected.
        String err = assertRejected("""
                function f(x:Int):[Int:@>=-16] -> (x-3)*(x+5)
                42
                """);
        assertTrue(err.contains("Cannot prove the declared return refinement of 'f'"),
                () -> "expected the no-proof rejection; got: " + err);
    }

    @Test
    void insufficientProof_isStaleHardError() {
        // A bare Leaf supplies no split, so the engine still can't close it.
        String err = assertRejected(STRUCTS + """
                function f(x:Int):[Int:@>=-16] -> (x-3)*(x+5)
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
    void builtinProofTypes_viaRequires_closeHardReturn() {
        // The proof vocabulary is imported from the builtin std.proof module —
        // no hand-declared Leaf/Split. The single `requires` routes the file
        // through the linker, which injects std.proof and FQN-resolves the
        // imported types so the proof tree translates and discharges.
        assertCompiles("""
                requires std.proof.{Leaf, Split}
                function f(x:Int):[Int:@>=0] -> x*(x-1)
                proof f = Split(x>=1, Leaf(), Leaf())
                42
                """);
    }

    @Test
    void isSparse_viaImportedSingletons_closesCompactly() {
        // Same flagship hard return as isSparse_closesPiecewiseViaAuthoredProof,
        // but the middle region [-5, 2] is the builtin `Singletons(x, -5, 2)`
        // directive (unfolded to the cut ladder by RefinementProof) instead of a
        // nine-deep hand-written Split chain — the Slice-2 "recursion to
        // singletons" surfaced through the imported proof vocabulary.
        assertCompiles("""
                requires std.proof.{Leaf, Split, Singletons}
                function isSparse(x:Int):[Int:@>=-16] -> (x-3)*(x+5)
                proof isSparse =
                  Split(x>=3, Leaf(),
                    Split(x<=-6, Leaf(),
                      Singletons(x, -5, 2)))
                42
                """);
    }

    @Test
    void isSparse_middleResidualAutoPeels_withoutSingletons() {
        // Same flagship hard return, but the middle region [-5, 2] is a BARE Leaf —
        // no Singletons directive. The x>=3 / x<=-6 cuts pin x to the finite residual
        // [-5, 2]; auto-peel detects that and enumerates it to singletons internally,
        // so the opaque product discharges point-by-point (min -16 at x=-1). Without
        // auto-peel this leaf stays open (the engine's sign-chart can't isolate x=-1).
        assertCompiles(STRUCTS + """
                function isSparse(x:Int):[Int:@>=-16] -> (x-3)*(x+5)
                proof isSparse =
                  Split(x>=3, Leaf(),
                    Split(x<=-6, Leaf(), Leaf()))
                42
                """);
    }

    @Test
    void openSidedResidual_isNotAutoPeeled_staysInsufficient() {
        // Only one cut, so the false side's residual is the infinite (-inf, 2].
        // Auto-peel enumerates only FINITE residuals, so it declines here and the
        // proof is honestly insufficient — the boundary of what auto-peel rescues.
        String err = assertRejected(STRUCTS + """
                function isSparse(x:Int):[Int:@>=-16] -> (x-3)*(x+5)
                proof isSparse = Split(x>=3, Leaf(), Leaf())
                42
                """);
        assertTrue(err.contains("isSparse"),
                () -> "expected an insufficient-proof rejection for 'isSparse'; got: " + err);
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
