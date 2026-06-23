package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.conservation.ConservationGraph;
import sibarum.pontif.conservation.ConservationGraph.Ledger;
import sibarum.pontif.conservation.ConservationQueries;
import sibarum.pontif.conservation.NoHalt;
import sibarum.pontif.runtime.ConservationReport.Result;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The conservation graph per the ratified algebra
 * ({@code docs/conservation-algebra.md}): three node kinds + metadata edges,
 * roles-not-fates, the sort-aware {@code DataConservative} under the capacity
 * law, residual flow as the located ignorance (lambdas, applications,
 * unresolved calls). Each demo writes a reviewable
 * {@code target/conservation/<name>.conservation.txt} artifact.
 */
class ConservationReportTest {

    private static final Path OUT = Path.of("target", "conservation");

    private Ledger ledger(String name, String src) throws Exception {
        ConservationReport.writeReport(OUT, name, src, name + ".ptf");
        Result r = ConservationReport.fromAltSource(src, name + ".ptf");
        assertInstanceOf(Result.Generated.class, r,
                () -> "expected ledger; got: " + ((Result.Failed) r).error());
        return ((Result.Generated) r).ledger();
    }

    private ConservationGraph graph(Ledger ledger, String fn) {
        return ledger.graph(fn).orElseThrow(() -> new AssertionError("no graph for " + fn));
    }

    private String print(String src) {
        Result r = ConservationReport.fromAltSource(src, "t.ptf");
        return ((Result.Generated) r).text();
    }

    // --- translation: the DataConservative gate ---

    private static final String LOSSY = """
            struct Source(name:Int, age:Int, email:Int)
            struct Target(fullName:Int, years:Int)
            function translate(s:Source):Target -> {fullName = s.name, years = s.age + 1}
            translate(Source(1, 2, 3))
            """;

    private static final String FIXED = """
            struct Source(name:Int, age:Int, email:Int)
            struct Target(fullName:Int, years:Int, contact:Int)
            function translate(s:Source):Target ->
              {fullName = s.name, years = s.age + 1, contact = s.email}
            translate(Source(1, 2, 3))
            """;

    @Test
    void lossyTranslation_failsDataConservative_andTheGraphShowsTheDrop() throws Exception {
        ConservationGraph translate = graph(ledger("translate-lossy", LOSSY), "translate");
        assertTrue(ConservationQueries.dataConservative(translate).isPresent(),
                "s.email never flows into Target — the silent loss must fail");
        String text = print(LOSSY);
        assertTrue(text.contains("s_0.email") && text.contains("UNTOUCHED"),
                () -> "expected the drop to be visible:\n" + text);
    }

    @Test
    void fixedTranslation_passesDataConservative() throws Exception {
        ConservationGraph translate = graph(ledger("translate-fixed", FIXED), "translate");
        assertTrue(ConservationQueries.dataConservative(translate).isEmpty(),
                () -> ConservationQueries.dataConservative(translate).orElse(""));
    }

    // --- swap: the reversibility witness ---

    @Test
    void swap_witnessesReversibility() throws Exception {
        String src = """
                function swap(p:[{Int, Bool}]):[{Bool, Int}] ->
                  match p { [{a, b}] -> {b, a} }
                swap({1, true})
                """;
        ConservationGraph swap = graph(ledger("swap", src), "swap");
        assertTrue(ConservationQueries.reversible(swap).isEmpty(),
                () -> ConservationQueries.reversible(swap).orElse(""));
        assertTrue(ConservationQueries.dataConservative(swap).isEmpty());
        assertFalse(ConservationQueries.duplicated(swap));
    }

    // --- the capacity law: Bool spent in branching conserves; Int doesn't ---

    @Test
    void boolSpentInBranching_isDataConservative() throws Exception {
        String src = """
                function pick(b:Bool):Int -> match b {
                  [@==true] -> 1
                  _ -> 2
                }
                pick(true)
                """;
        ConservationGraph pick = graph(ledger("pick-bool", src), "pick");
        assertTrue(ConservationQueries.dataConservative(pick).isEmpty(),
                "a Bool's whole content is one bit — branching on it spends all of it");
    }

    @Test
    void intSpentOnlyInBranching_isNotDataConservative() throws Exception {
        String src = """
                function clamp(x:Int):Int -> match x {
                  [@>0] -> x
                  _ -> 0
                }
                clamp(5)
                """;
        ConservationGraph clamp = graph(ledger("clamp", src), "clamp");
        assertTrue(ConservationQueries.dataConservative(clamp).isPresent(),
                "an Int branched on yields one bit of many — not conservation");
    }

    // --- duplication breaks the bijection ---

    @Test
    void duplication_breaksTheBijection() throws Exception {
        String src = """
                function dup(p:[{Int, Bool}]):[{Int, Int}] ->
                  match p { [{a, _}] -> {a, a} }
                dup({1, true})
                """;
        ConservationGraph dup = graph(ledger("dup", src), "dup");
        assertTrue(ConservationQueries.duplicated(dup));
        assertTrue(ConservationQueries.reversible(dup).isPresent());
    }

    // --- the headline flip: nested destructure/discrimination now TRACES ---

    @Test
    void nestedMatch_isTraced_notOpaque() throws Exception {
        // v1 marked this OPAQUE (vocabulary poverty). The algebra traces it:
        // the inner match is a Branch node, and DataConservative now fails on
        // the MERITS — x is untouched on the else-path — with the
        // classification visible instead of a shrug.
        String src = """
                function f(x:Int, y:Int):Int ->
                  let inner = match y { [@>0] -> x
                  _ -> 0 }
                  inner + y
                """;
        ConservationGraph f = graph(ledger("nested", src), "f");
        String text = print(src);
        assertFalse(text.contains("RESIDUAL") || text.contains("untraceable"),
                () -> "nested discrimination must trace:\n" + text);
        assertTrue(ConservationQueries.dataConservative(f).isPresent(),
                "x is dropped on the else path — an honest failure, not ignorance");
        assertTrue(text.contains("UNTOUCHED"), () -> text);
    }

    // --- composition: callee summaries substitute over the call DAG ---

    @Test
    void helperDelegation_composes_andCertifies() throws Exception {
        // The point of composition: real code calls helpers. inc's summary
        // (years' content reaches the result) substitutes at translate's call
        // site, and DataConservative certifies end-to-end.
        String src = """
                struct Source(name:Int, age:Int, email:Int)
                struct Target(fullName:Int, years:Int, contact:Int)
                function inc(years:Int):Int -> years + 1
                function translate(s:Source):Target ->
                  {fullName = s.name, years = inc(s.age), contact = s.email}
                translate(Source(1, 2, 3))
                """;
        ConservationGraph translate =
                graph(ledger("translate-composed", src), "translate");
        assertTrue(ConservationQueries.dataConservative(translate).isEmpty(),
                () -> ConservationQueries.dataConservative(translate).orElse(""));
    }

    @Test
    void lossyHelper_composesTheLossIntoTheCaller() throws Exception {
        // The helper ignores its second parameter — the caller's atom is
        // dropped INSIDE the callee, and composition surfaces it.
        String src = """
                struct Source(name:Int, age:Int, email:Int)
                struct Target(fullName:Int, years:Int, contact:Int)
                function first(a:Int, b:Int):Int -> a
                function translate(s:Source):Target ->
                  {fullName = s.name, years = first(s.age, s.email), contact = s.name}
                translate(Source(1, 2, 3))
                """;
        ConservationGraph translate =
                graph(ledger("translate-lossy-helper", src), "translate");
        assertTrue(ConservationQueries.dataConservative(translate).isPresent(),
                "s.email dies inside first(a, b) -> a — composition must surface it");
    }

    @Test
    void calleeBranchingSpend_creditsTheCallersBool() throws Exception {
        // pick branches on its Bool on every path; the caller's Bool is
        // therefore spent — credited through the summary.
        String src = """
                function pick(b:Bool):Int -> match b {
                  [@==true] -> 1
                  _ -> 2
                }
                function wrap(flag:Bool):Int -> pick(flag)
                wrap(true)
                """;
        ConservationGraph wrap = graph(ledger("wrap-bool", src), "wrap");
        assertTrue(ConservationQueries.dataConservative(wrap).isEmpty(),
                () -> ConservationQueries.dataConservative(wrap).orElse(""));
    }

    @Test
    void overloadedCallee_branchesOverCandidates() throws Exception {
        // Dispatch-as-Branch: each overload is an arm; the property must hold
        // on every arm. One conserving + one dropping overload -> fails.
        String src = """
                function h(x:[Int:@>0], y:Int):Int -> x + y
                function h(x:[Int:0], y:Int):Int -> y
                function g(a:Int, b:Int):Int -> h(a, b)
                g(1, 2)
                """;
        ConservationGraph g = graph(ledger("overloaded", src), "g");
        assertTrue(ConservationQueries.dataConservative(g).isPresent(),
                "the [Int:0] overload drops x — some dispatch arm loses a");
    }

    // --- recursion: the fixpoint (per-cycle Kleene, optimistic seed) ---

    @Test
    void recursion_factorial_failsHonestly_notViaResidual() throws Exception {
        // The fixpoint converges fact's summary (n: NONE — the base path
        // returns the constant 1 — but spent everywhere), so the verdict is
        // on the MERITS: an Int spent on one branching bit, not located
        // ignorance.
        String src = """
                function fact(n:Int):Int -> match n {
                  [@==0] -> 1
                  _ -> n * fact(n - 1)
                }
                fact(3)
                """;
        ConservationGraph fact = graph(ledger("fact", src), "fact");
        assertTrue(ConservationQueries.dataConservative(fact).isPresent(),
                "the base path spends n on one bit and returns a constant");
        String text = print(src);
        assertFalse(text.contains("?("),
                () -> "the recursive call must trace, not stay residual:\n" + text);
        assertTrue(text.contains("via fact"), () -> text);
    }

    @Test
    void recursion_thatCarriesContentOnEveryPath_certifies() throws Exception {
        // Base path returns n verbatim; the recursive path carries it through
        // a recoverable chain (the call's converged summary, then +1). The
        // inductive hypothesis discharges: content reaches the return on
        // every path.
        String src = """
                function f(n:Int):Int -> match n {
                  [@==0] -> n
                  _ -> f(n - 1) + 1
                }
                f(3)
                """;
        ConservationGraph f = graph(ledger("recursion-certifies", src), "f");
        assertTrue(ConservationQueries.dataConservative(f).isEmpty(),
                () -> ConservationQueries.dataConservative(f).orElse(""));
    }

    @Test
    void mutualRecursion_converges_andJudgesOnTheMerits() throws Exception {
        // even/odd: a two-member cycle. Both converge; both fail honestly
        // (constant base arms drop n's content) with no residual anywhere.
        String src = """
                function isEven(n:Int):Int -> match n {
                  [@==0] -> 1
                  _ -> isOdd(n - 1)
                }
                function isOdd(n:Int):Int -> match n {
                  [@==0] -> 0
                  _ -> isEven(n - 1)
                }
                isEven(4)
                """;
        Ledger ledger = ledger("mutual-recursion", src);
        assertTrue(ConservationQueries.dataConservative(graph(ledger, "isEven")).isPresent());
        assertTrue(ConservationQueries.dataConservative(graph(ledger, "isOdd")).isPresent());
        String text = print(src);
        assertFalse(text.contains("?(") || text.contains("recursive"),
                () -> "a cycle must converge, not stay residual:\n" + text);
    }

    @Test
    void ungroundedLoop_certifiesVacuously_perThePartialCorrectnessRuling() throws Exception {
        // The ruling (docs/conservation-algebra.md): conservation claims
        // quantify over COMPLETED evaluations — the same partial-correctness
        // reading as the receipt graph's inductive hypothesis. A loop that
        // never completes has nothing to violate, so the optimistic seed is
        // the fixpoint and the claim certifies vacuously.
        String src = """
                function loop(n:Int):Int -> loop(n)
                loop(1)
                """;
        ConservationGraph loop = graph(ledger("ungrounded-loop", src), "loop");
        assertTrue(ConservationQueries.dataConservative(loop).isEmpty(),
                () -> ConservationQueries.dataConservative(loop).orElse(""));
        String text = print(src);
        assertFalse(text.contains("?("), () -> text);
        // The vacuous certificate no longer stands silent: the ledger NAMES
        // the divergence beside it.
        assertTrue(text.contains("no-halt:") && text.contains("re-enters 'loop'"),
                () -> "the ledger must name what makes the pass vacuous:\n" + text);
    }

    @Test
    void nonCycleCaller_composesThroughTheRecursiveCallee() throws Exception {
        // wrap is not recursive — it is merely blocked behind fact's cycle.
        // It must compose through fact's CONVERGED summary and fail with the
        // honest reason, not wear a false "recursive" label.
        String src = """
                function fact(n:Int):Int -> match n {
                  [@==0] -> 1
                  _ -> n * fact(n - 1)
                }
                function wrap(m:Int):Int -> fact(m)
                wrap(3)
                """;
        ConservationGraph wrap = graph(ledger("wrap-recursive", src), "wrap");
        assertTrue(ConservationQueries.dataConservative(wrap).isPresent(),
                "m dies inside fact — composition must surface it");
        String text = print(src);
        assertFalse(text.contains("?(") || text.contains("recursive"),
                () -> "wrap composes through the converged summary:\n" + text);
        assertTrue(text.contains("via fact"), () -> text);
    }

    @Test
    void callerOfAnOverload_isNotLabeledRecursive() throws Exception {
        // Phase B falsely treated callers of overloaded names as cycle
        // members (overloaded names never get summaries), so top's call to g
        // wore a "recursive" residual label on a chain with no recursion.
        String src = """
                function h(x:[Int:@>0], y:Int):Int -> x + y
                function h(x:[Int:0], y:Int):Int -> y
                function g(a:Int, b:Int):Int -> h(a, b)
                function top(c:Int, d:Int):Int -> g(c, d)
                top(1, 2)
                """;
        String text = print(src);
        assertFalse(text.contains("recursive"),
                () -> "no recursion anywhere in top -> g -> h:\n" + text);
        assertTrue(text.contains("via g"),
                () -> "top must compose through g's summary:\n" + text);
    }

    @Test
    void fixpoint_lossPropagatesAroundTheCycle_overMultipleRounds() throws Exception {
        // Declaration order opposes the call chain (a -> b -> c -> a, with
        // the local loss in c), so the degradation crosses one function per
        // round: c drops in round 1, b in round 2, a in round 3. Convergence
        // within the cap, honest verdicts for all three.
        String src = """
                function a(n:Int):Int -> b(n)
                function b(n:Int):Int -> c(n)
                function c(n:Int):Int -> match n {
                  [@==0] -> 1
                  _ -> a(n - 1)
                }
                a(2)
                """;
        Ledger ledger = ledger("fixpoint-rounds", src);
        for (String fn : new String[] {"a", "b", "c"}) {
            assertTrue(ConservationQueries.dataConservative(graph(ledger, fn)).isPresent(),
                    fn + ": n's content never reaches a return — must fail on the merits");
        }
        String text = print(src);
        assertFalse(text.contains("?(") || text.contains("recursive"), () -> text);
    }

    // --- No-Halt: the divergence fact (the sound corner of non-halting) ---

    @Test
    void noHalt_groundedRecursion_makesNoClaim() throws Exception {
        // Over-trigger guards: factorial and even/odd terminate (for n >= 0)
        // and proving their negative-input divergence needs arithmetic the
        // ledger doesn't have. Silence — no claim, never a halting verdict.
        String src = """
                function fact(n:Int):Int -> match n {
                  [@==0] -> 1
                  _ -> n * fact(n - 1)
                }
                function isEven(n:Int):Int -> match n {
                  [@==0] -> 1
                  _ -> isOdd(n - 1)
                }
                function isOdd(n:Int):Int -> match n {
                  [@==0] -> 0
                  _ -> isEven(n - 1)
                }
                fact(3)
                """;
        Ledger ledger = ledger("nohalt-grounded", src);
        assertTrue(NoHalt.of(ledger).isEmpty(),
                () -> "grounded recursion must stay unclaimed: " + NoHalt.of(ledger));
    }

    @Test
    void noHalt_verbatimReentry_marksThePath_notTheFunction() throws Exception {
        // The recursive arm re-enters f with n unchanged — that PATH never
        // halts (pure + strict: same args, same arms, forever). The base path
        // grounds the function, so no function-level claim.
        String src = """
                function f(n:Int):Int -> match n {
                  [@==0] -> 0
                  _ -> f(n)
                }
                f(0)
                """;
        Ledger ledger = ledger("nohalt-reentry-path", src);
        assertFalse(NoHalt.of(ledger).containsKey("f"),
                "the base path grounds f — no function-level claim");
        String text = print(src);
        assertTrue(text.contains("never halts (re-enters 'f' with its own arguments)"),
                () -> "the divergent path must be marked:\n" + text);
    }

    @Test
    void noHalt_argumentPermutation_isStillReentry() throws Exception {
        // Verbatim PERMUTATION of the params: the orbit is finite, so pure
        // re-entry revisits a prior state — never halts.
        String src = """
                function spin(a:Int, b:Int):Int -> spin(b, a)
                spin(1, 2)
                """;
        Ledger ledger = ledger("nohalt-permutation", src);
        assertTrue(NoHalt.of(ledger).containsKey("spin"),
                () -> "swap-args re-entry has a finite orbit: " + NoHalt.of(ledger));
    }

    @Test
    void noHalt_propagatesToCallers() throws Exception {
        // wrap's only path calls loop; loop never halts; therefore wrap never
        // halts — the gfp gives caller propagation for free.
        String src = """
                function loop(n:Int):Int -> loop(n)
                function wrap(m:Int):Int -> wrap2(m)
                function wrap2(m:Int):Int -> loop(m)
                wrap(1)
                """;
        Ledger ledger = ledger("nohalt-propagation", src);
        var noHalt = NoHalt.of(ledger);
        assertTrue(noHalt.containsKey("loop") && noHalt.containsKey("wrap2")
                        && noHalt.containsKey("wrap"),
                () -> "divergence must reach transitive callers: " + noHalt);
        assertTrue(noHalt.get("wrap2").contains("calls 'loop', which never halts"),
                () -> noHalt.get("wrap2"));
    }

    @Test
    void noHalt_ungroundedMutualCycle_bothClaimed() throws Exception {
        // Neither has an arm without a call into the cycle — no evaluation
        // can exit. Both stay in the gfp.
        String src = """
                function ping(n:Int):Int -> pong(n - 1)
                function pong(n:Int):Int -> ping(n + 1)
                ping(1)
                """;
        Ledger ledger = ledger("nohalt-mutual", src);
        var noHalt = NoHalt.of(ledger);
        assertTrue(noHalt.containsKey("ping") && noHalt.containsKey("pong"),
                () -> noHalt.toString());
    }

    @Test
    void noHalt_callInDiscriminantPosition_counts() throws Exception {
        // The scrutinee is evaluated before any arm — a never-halting call
        // there diverges the whole match.
        String src = """
                function loop(n:Int):Int -> loop(n)
                function d(n:Int):Int -> match loop(n) {
                  [Int:@>0] -> 1
                  _ -> 0
                }
                d(1)
                """;
        Ledger ledger = ledger("nohalt-discriminant", src);
        assertTrue(NoHalt.of(ledger).containsKey("d"),
                () -> "the discriminant call is evaluated on every arm: "
                        + NoHalt.of(ledger));
    }

    @Test
    void noHalt_unusedLetCall_isAKnownMiss() throws Exception {
        // PINNED LIMITATION: the let-bound call is evaluated at runtime
        // (strict) but its flow never reaches the result, so the walk cannot
        // see it. A miss on the sound side — no claim is made, and silence
        // is never a halting verdict. If this assertion ever flips, the walk
        // gained dead-flow coverage: update the docs with it.
        String src = """
                function loop(n:Int):Int -> loop(n)
                function m(n:Int):Int ->
                  let x = loop(n)
                  5
                m(1)
                """;
        Ledger ledger = ledger("nohalt-unused-let", src);
        assertTrue(NoHalt.of(ledger).containsKey("loop"));
        assertFalse(NoHalt.of(ledger).containsKey("m"),
                "dead-flow calls are invisible to the walk — the documented miss");
    }

    @Test
    void tupleOfConstrainedStructs_discriminates_notIrrefutable() throws Exception {
        // A tuple pattern whose components carry literal constraints (n==0)
        // DOES discriminate — isRefutable must look inside the tuple, so the
        // branch records the n fields as consulted rather than reading
        // "branch (irrefutable)".
        String src = """
                struct P(n:Int, k:Int)
                function pick(a:P, b:P):Int -> match {a, b}
                  [{P(0, j), P(0, m)}] -> j + m
                  [{P(i, j), P(x, m)}] -> i + x
                pick(P(0,1), P(0,2))
                """;
        ConservationGraph g = graph(ledger("tuple-discriminates", src), "pick");
        boolean discriminates = g.nodes().values().stream().anyMatch(
                n -> n instanceof sibarum.pontif.conservation.FlowNode.Branch b
                        && !b.discriminants().isEmpty());
        assertTrue(discriminates,
                "the tuple-of-structs match discriminates on n — isRefutable must "
                        + "look inside the tuple, not treat _tuple as irrefutable");
    }
}
