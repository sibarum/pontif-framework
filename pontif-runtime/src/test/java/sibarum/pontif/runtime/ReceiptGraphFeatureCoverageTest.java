package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization coverage for the receipt-graph drafter/issuer against the
 * <b>post-refactor</b> language features (dispatch overloads, brace aggregates,
 * ADT-recursion, {@code assign proof}-granted refinements, stream queries).
 * The pre-existing {@code ReceiptGraphReportTest} only exercises guard-only
 * match arms ({@code [@>0] -> …}); nothing covered destructuring arms or the
 * newer surfaces, which is where the breakage hides.
 *
 * <p>Tests marked {@code _BUG} lock CURRENT (wrong) behavior so the suite is
 * green and the defect is documented + tripwired: fixing the drafter flips the
 * test red, which is the signal to update it. Findings are written up in
 * {@code docs/receipt-graph-overhaul.md} ("Step 1 findings").
 */
class ReceiptGraphFeatureCoverageTest {

    private static String report(String src, String name) {
        ReceiptGraphReport.Result r = ReceiptGraphReport.fromAltSource(src, name);
        return assertInstanceOf(ReceiptGraphReport.Result.Generated.class, r,
                () -> "expected a generated report; got " + r).text();
    }

    // --- Baselines that DO work on the new surfaces ------------------------

    @Test
    void dispatchOverloads_bothDischarge() {
        // Two refinement-discriminated overloads of `clamp`, each with a
        // provable [Int:@>=0] return. The CallSig/dispatch rework didn't break
        // per-overload drafting + discharge.
        String text = report("""
                module m
                function clamp(x:[Int:@<0]):[Int:@>=0] -> 0
                function clamp(x:[Int:@>=0]):[Int:@>=0] -> x
                clamp(-4)
                """, "clamp.ptf");
        assertFalse(text.contains("NOT DISCHARGED"),
                () -> "both clamp overloads should discharge:\n" + text);
    }

    @Test
    void braceArmMatch_withRefinedReturn_discharges() {
        // Brace-delimited match arms + a numeric refined return still draft and
        // discharge — the brace syntax itself is fine when the arm binds no
        // destructured variables (guard-only arm + bare `_`).
        String text = report("""
                module m
                function pos(x:Int):[Int:@>=0] -> match x {
                  [@>0] -> x
                  _ -> 0
                }
                pos(5)
                """, "pos.ptf");
        assertTrue(text.contains("pos  :  r_0 >= 0"), () -> text);
        assertFalse(text.contains("NOT DISCHARGED"), () -> text);
    }

    // --- Finding 1 (FIXED): destructuring arms inline to real equations ----

    @Test
    void destructuringArm_inlinesProjections_notALambdaWrapper() {
        // FINDING 1 (root cause), FIXED. A destructuring match arm `[{a,b}] ->
        // {b,a}` desugars (single-file) to `let a = p._0 in let b = p._1 in
        // {b,a}`, which compileSymExpr would otherwise encode as an un-reduced
        // App(Lam(...)) wrapper. The drafter now inlines those projection lets
        // before hoisting, so the receipt is a plain value equation with the
        // binders replaced by their projections (a := p_0._0, b := p_0._1).
        String text = report("""
                module m
                function swap(p:[{Int, Bool}]):[{Bool, Int}] ->
                  match p { [{a, b}] -> {b, a} }
                swap({1, true})
                """, "swap.ptf");
        assertTrue(text.contains("receipt: r_0 == _tuple{_0=p_0._1, _1=p_0._0}"),
                () -> "expected the inlined value equation, no lambda wrapper:\n" + text);
        assertFalse(text.contains("->") && text.contains("(p_0._1)(p_0._0)"),
                () -> "the un-reduced beta-redex must be gone:\n" + text);
    }

    @Test
    void recursiveAdtArm_dischargesLikeFactorial() {
        // FINDING 1 consequence, FIXED — the one that actually costs a proof.
        // `len` over a Nil|Cons list has a trivially-true [Int:@>=0] return:
        // base 0, recursive 1 + len(t) with the back-reference IH r_1 >= 0. It
        // now discharges exactly like factorial: the arm's projection let
        // (t = xs.tail) is inlined, so the call hoists as len(xs_0.tail) (not the
        // dangling binder len(t)) and the body equation is a clean `1 + r_1`
        // that BoundAnalysis closes under the IH.
        String text = report("""
                module m
                struct Nil()
                struct Cons(head:Int, tail:[Nil|Cons])
                function len(xs:[Nil|Cons]):[Int:@>=0] -> match xs
                  [Nil()] -> 0
                  [Cons(h, t)] -> 1 + len(t)
                len(Cons(1, Nil()))
                """, "len.ptf");
        // The recursive call now carries the projection as its argument…
        assertTrue(text.contains("call: len(xs_0.tail) -> r_1: [Int: @ >= 0]"),
                () -> "recursive call arg should be the inlined projection:\n" + text);
        // …the body equation is the clean linear form…
        assertTrue(text.contains("receipt: r_0 == 1 + r_1"),
                () -> "body equation should be the inlined linear form:\n" + text);
        // …and the whole obligation discharges (both branches).
        assertFalse(text.contains("NOT DISCHARGED"),
                () -> "len's r_0 >= 0 should now discharge on both branches:\n" + text);
    }

    // --- Finding 2: assign-proof-granted refinements are invisible ---------

    @Test
    void assignProofGrantedRefinement_invisibleToReceiptGraph_BUG() {
        // FINDING 2. `assign proof isSparse(x):[… -> [Int:@>=-16]]` GRANTS a
        // return refinement the return gate honors (AssignProofTest proves the
        // program compiles on the strength of it). But the receipt drafter
        // reads only the DECLARED base return `:[Int]`, so the graph shows
        // `r_0: Int` with "no return refinement -- nothing to prove". The
        // receipt view and the gate disagree: the granted obligation — and its
        // proof-discharge — are entirely absent from the proof artifact.
        String text = report("""
                module m
                function isSparse(x:Int):[Int] -> (x-3)*(x+5)
                assign proof isSparse(x:Int):[
                  (match x
                    [@>=3]  -> this(x)
                    [@<=-6] -> this(x)
                    [_]     -> this(x)
                  ) ->
                  [Int:@ >= -16]
                ]
                isSparse(5)
                """, "isSparse.ptf");
        // BUG: the granted [Int:@>=-16] never reaches the receipt graph.
        assertTrue(text.contains("isSparse(x_0: Int) : r_0: Int"),
                () -> "expected the DECLARED base return only (Finding 2):\n" + text);
        assertTrue(text.contains("isSparse  (no return refinement -- nothing to prove)"),
                () -> "BUG: assign-proof-granted [Int:@>=-16] is missing from the "
                        + "receipt graph — when fixed, the obligation r_0 >= -16 "
                        + "should appear discharged-via-proof:\n" + text);
    }

    // --- Finding 3: stream-query desugaring emits an empty else-branch -----

    @Test
    void streamQuery_draftsIterHelper_withEmptyElseBranch_characterization() {
        // FINDING 3 (lower severity). A refined stream query `&s:[Int:@>1]`
        // desugars to an `$iter$` helper whose filter produces a guarded
        // keep-branch AND an unconditional else-branch that carries NO initial
        // receipt (the implicit drop). Plus the caller's receipt is another
        // lambda-app blob (Finding 1 family). Characterized, not yet judged
        // right/wrong — the empty branch is a smell an obligation would trip on.
        String text = report("""
                module m
                function bigs():Stream[Int] ->
                  let s = {1, 2, 3, 4}
                  &s:[Int:@ > 1].all()
                bigs()
                """, "bigs.ptf");
        assertTrue(text.contains("bigs$iter$0"),
                () -> "expected a drafted $iter$ helper for the stream query:\n" + text);
        assertTrue(text.contains("branch [$q0_0 > 1]:"),
                () -> "expected the filter keep-branch guard:\n" + text);
    }
}
