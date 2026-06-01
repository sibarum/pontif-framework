package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adversarial coverage for the proof-authoring surface — deliberately trying to
 * break it. The load-bearing property: a syntactically valid, even
 * domain-covering, proof can rescue a <em>true</em>-but-hard return but can
 * <b>never</b> launder a <em>false</em> one (a false leaf simply won't
 * discharge). Plus the v1 guards and translator robustness.
 */
class ProofAuthoringAdversarialTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private CompileResult compile(String src) {
        return compiler.compileAlt(src, "adv.ptf");
    }

    private void assertCompiles(String src) {
        CompileResult r = compile(src);
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "expected success; got: " + ((CompileResult.Failed) r).error().text());
    }

    private String assertRejected(String src) {
        CompileResult r = compile(src);
        assertInstanceOf(CompileResult.Failed.class, r, "expected failure");
        return ((CompileResult.Failed) r).error().text();
    }

    private static final String STRUCTS = """
            struct Leaf()
            struct Split(p:Bool, whenTrue:[Leaf|Split], whenFalse:[Leaf|Split])
            """;

    // --- Soundness: a proof can't make a false return pass --------------------

    @Test
    void coveringSplit_cannotLaunderFalseReturn() {
        // bad(x):[Int:@>0] -> x is FALSE (x can be 0 or negative). The split
        // x>=1 / x<1 covers all of Z, but the x<1 leaf must prove x>0 for
        // x<=0 — which is false — so it never discharges. Rejected.
        String err = assertRejected(STRUCTS + """
                function bad(x:Int):[Int:@>0] -> x
                proof bad = Split(x>=1, Leaf(), Leaf())
                42
                """);
        assertTrue(err.contains("supplied proof for 'bad'") && err.contains("stale or insufficient"),
                () -> "a covering split must not launder a false return; got: " + err);
    }

    @Test
    void deeperSplitting_stillCannotProveFalse() {
        // No matter how finely you split, the false region survives. Peel the
        // boundary further; the x<=0 leaf still can't prove x>0.
        String err = assertRejected(STRUCTS + """
                function bad(x:Int):[Int:@>0] -> x
                proof bad = Split(x>=1, Leaf(),
                              Split(x>=0, Leaf(), Leaf()))
                42
                """);
        assertTrue(err.contains("stale or insufficient"),
                () -> "extra splits can't rescue a false claim; got: " + err);
    }

    // --- Predicates that look like they should help but don't -----------------

    @Test
    void splitOverPhantomVariable_doesNotDischarge() {
        // The split is on `y`, which isn't a parameter — it's never bound in the
        // path facts, so the guard constrains nothing and the proof fails.
        String err = assertRejected(STRUCTS + """
                function f(x:Int):[Int:@>=0] -> x*(x-1)
                proof f = Split(y>=1, Leaf(), Leaf())
                42
                """);
        assertTrue(err.contains("stale or insufficient"),
                () -> "a split on a non-parameter can't discharge; got: " + err);
    }

    @Test
    void selfInSplitPredicate_doesNotMagicallyDischarge() {
        // `@` in a split predicate is NOT the parameter — proof predicates range
        // over params (x), not the refinement subject. So `@>=1` constrains
        // nothing useful here and the proof fails. (Documents the params-only rule.)
        String err = assertRejected(STRUCTS + """
                function f(x:Int):[Int:@>=0] -> x*(x-1)
                proof f = Split(@>=1, Leaf(), Leaf())
                42
                """);
        assertTrue(err.contains("stale or insufficient"),
                () -> "`@` in a split predicate must not stand in for the param; got: " + err);
    }

    // --- Translator robustness ------------------------------------------------

    @Test
    void nonComparisonSplitPredicate_isHardError() {
        // `x>0 & x<5` is an And, not a Cmp — the kernel only admits comparisons.
        String err = assertRejected(STRUCTS + """
                function f(x:Int):[Int:@>=0] -> x*(x-1)
                proof f = Split(x>0 & x<5, Leaf(), Leaf())
                42
                """);
        assertTrue(err.contains("could not be used") && err.contains("comparison"),
                () -> "non-comparison split predicate must be rejected; got: " + err);
    }

    @Test
    void nonProofStructAsTree_isHardError() {
        // A struct that isn't Leaf/Split can't be a proof node.
        String err = assertRejected(STRUCTS + """
                struct Other(a:Int)
                function f(x:Int):[Int:@>=0] -> x*(x-1)
                proof f = Other(1)
                42
                """);
        assertTrue(err.contains("could not be used") && err.contains("unknown proof constructor"),
                () -> "a non-Leaf/Split struct must be rejected; got: " + err);
    }

    // --- v1 binding guards ----------------------------------------------------

    @Test
    void proofOnOverloadedFunction_isHardError() {
        String err = assertRejected(STRUCTS + """
                function f(x:[Int:@>0]):[Int:@>=0] -> x
                function f(x:[Int:@<=0]):[Int:@>=0] -> 0
                proof f = Split(x>=1, Leaf(), Leaf())
                42
                """);
        assertTrue(err.contains("overloaded"),
                () -> "proofs on overloaded functions are not supported in v1; got: " + err);
    }

    @Test
    void proofOnMatchFunction_isHardError() {
        // A match body drafts to multiple branches; v1 binds only branch 0.
        String err = assertRejected(STRUCTS + """
                function g(n:Int):[Int:@>=0] -> match n
                  [@<0 ] -> 0
                  [@>=0] -> n
                proof g = Leaf()
                42
                """);
        assertTrue(err.contains("multi-branch"),
                () -> "proofs on multi-branch functions are not supported in v1; got: " + err);
    }

    // --- Redundant but well-formed proof is tolerated -------------------------

    @Test
    void redundantWellFormedProof_onProvableReturn_compiles() {
        // The engine already discharges x*x >= 0 (sign square rule); a
        // well-formed proof is simply never consulted — not an error.
        assertCompiles(STRUCTS + """
                function sq(x:Int):[Int:@>=0] -> x*x
                proof sq = Split(x>=0, Leaf(), Leaf())
                42
                """);
    }
}
