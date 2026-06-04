package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.conservation.ConservationGraph;
import sibarum.pontif.conservation.ConservationGraph.Ledger;
import sibarum.pontif.conservation.ConservationQueries;
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
                function swap(p:[(Int, Bool)]):[(Bool, Int)] ->
                  match p { [(a, b)] -> (b, a) }
                swap((1, true))
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
                function dup(p:[(Int, Bool)]):[(Int, Int)] ->
                  match p { [(a, _)] -> (a, a) }
                dup((1, true))
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

    // --- the located ignorance: recursion (until the fixpoint slice) ---

    @Test
    void recursion_staysResidual_andFailsClosed() throws Exception {
        String src = """
                function fact(n:Int):Int -> match n {
                  [@==0] -> 1
                  _ -> n * fact(n - 1)
                }
                fact(3)
                """;
        ConservationGraph fact = graph(ledger("fact", src), "fact");
        assertTrue(ConservationQueries.dataConservative(fact).isPresent(),
                "flow through an unresolved call never certifies");
        assertTrue(print(src).contains("?("), () -> print(src));
    }
}
