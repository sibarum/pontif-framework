package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code assign proof} return-refinement surface: the function declares a
 * base return and the proof <b>grants and proves</b> the refinement via a
 * case-function whose ordered {@code [guard] ->} arms cut the domain (lowered to
 * the same Split/Leaf engine the {@code proof = <tree>} form uses). Additive —
 * the old surface and the conservation {@code proof = …} statement are untouched.
 */
class AssignProofTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private void assertCompiles(String src) {
        CompileResult r = compiler.compileAlt(src, "assignproof.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "expected compile success; got: "
                        + ((CompileResult.Failed) r).error().text());
    }

    private String assertRejected(String src) {
        CompileResult r = compiler.compileAlt(src, "assignproof.ptf");
        assertInstanceOf(CompileResult.Failed.class, r, "expected compile failure");
        return ((CompileResult.Failed) r).error().text();
    }

    @Test
    void caseFunctionProof_grantsAndProvesReturn() {
        // Function declares base [Int]; the proof grants [Int:@>=-16] and proves it
        // by cutting the domain at x>=3 / x<=-6. The middle [-5,2] auto-peels
        // (Slice 2). No Split tree, no std.proof import.
        assertCompiles("""
                function isSparse(x:Int):[Int] -> (x-3)*(x+5)
                assign proof isSparse(x:Int):[
                  (match x
                    [@>=3]  -> this(x)
                    [@<=-6] -> this(x)
                    [_]     -> this(x)
                  ) ->
                  [Int:@ >= -16]
                ]
                42
                """);
    }

    @Test
    void caseFunctionProof_insufficientCut_isRejected() {
        // One cut leaves the infinite residual (-inf, 2], which auto-peel won't
        // enumerate, so [Int:@>=-16] can't be discharged there — honest rejection.
        String err = assertRejected("""
                function isSparse(x:Int):[Int] -> (x-3)*(x+5)
                assign proof isSparse(x:Int):[
                  (match x
                    [@>=3] -> this(x)
                    [_]    -> this(x)
                  ) ->
                  [Int:@ >= -16]
                ]
                42
                """);
        assertTrue(err.contains("isSparse"),
                () -> "expected a rejection mentioning 'isSparse'; got: " + err);
    }

    @Test
    void proofDispatch_twoRegions_eachGrantsItsReturn() {
        // proveBranch: `match d` returns (x-3)*(x+5) for d>=0, else d. Two proofs,
        // one per region — proof dispatch. d<0 grants [Int:d] (result==d, trivial
        // since that branch returns d); d>=0 grants [Int:@>=-16], proven by cutting
        // x. Each proof's parameter-refinement region selects the branch it proves.
        assertCompiles("""
                function proveBranch(d:Int, x:Int):[Int] -> (match d
                    [@>=0] -> (x-3)*(x+5)
                    [_]    -> d
                )
                assign proof proveBranch(d:[Int:@<0], x:Int):[Int:d]
                assign proof proveBranch(d:[Int:@>=0], x:Int):[
                  (match x
                    [@>=3]  -> this(d, x)
                    [@<=-6] -> this(d, x)
                    [_]     -> this(d, x)
                  ) ->
                  [Int:@ >= -16]
                ]
                42
                """);
    }

    @Test
    void proofDispatch_regionMatchingNoBranch_isRejected() {
        // The body splits d at 0, but a proof claims the region d>=5 — not any
        // branch's effective region. No match → rejected.
        String err = assertRejected("""
                function proveBranch(d:Int, x:Int):[Int] -> (match d
                    [@>=0] -> (x-3)*(x+5)
                    [_]    -> d
                )
                assign proof proveBranch(d:[Int:@>=5], x:Int):[Int:@>=-16]
                42
                """);
        assertTrue(err.contains("proveBranch"),
                () -> "expected a region-match rejection for 'proveBranch'; got: " + err);
    }

    @Test
    void assignProof_unknownFunction_isRejected() {
        String err = assertRejected("""
                function f(x:Int):[Int] -> x
                assign proof ghost(x:Int):[Int:@>=0]
                42
                """);
        assertTrue(err.contains("unknown function") && err.contains("ghost"),
                () -> "expected unknown-function rejection; got: " + err);
    }

    // --- Call-site return narrowing (end-to-end via the let-claim gate) -------

    private static final String PROVE_BRANCH = """
            function proveBranch(d:Int, x:Int):[Int] -> (match d
                [@>=0] -> (x-3)*(x+5)
                [_]    -> d
            )
            assign proof proveBranch(d:[Int:@<0], x:Int):[Int:d]
            assign proof proveBranch(d:[Int:@>=0], x:Int):[
              (match x [@>=3]->this(d,x) [@<=-6]->this(d,x) [_]->this(d,x)) -> [Int:@>=-16]
            ]
            """;

    @Test
    void callSiteNarrowing_makesDisjointInBodyClaimProvablyFalse() {
        // The construction gate statically checks in-body let-claims via
        // NarrowingInference. proveBranch(5, 0) narrows to the d>=0 region's
        // [Int:@>=-16], so a claim of the DISJOINT [Int:@<-16] is provably false →
        // hard rejection. Without call-site narrowing the call would be base [Int],
        // the claim merely unknown (deferred to a runtime check) and this would
        // compile. So the rejection here is exactly what the narrowing buys.
        String err = assertRejected(PROVE_BRANCH + """
                function check():Int -> { let y:[Int:@<-16] = proveBranch(5, 0)
                  y }
                check()
                """);
        assertTrue(err.toLowerCase().contains("claim") || err.contains("y")
                        || err.contains("@") || err.toLowerCase().contains("prove"),
                () -> "expected the disjoint in-body claim to be rejected; got: " + err);
    }
}
