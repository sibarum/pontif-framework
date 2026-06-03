package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.conservation.ConservationLedger;
import sibarum.pontif.conservation.ConservationLedger.ConservationNode;
import sibarum.pontif.conservation.ConservationQueries;
import sibarum.pontif.runtime.ConservationReport.Result;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conservation receipts, Slice 1 — the ledger, its reading, and the gate
 * demonstrated programmatically. Each demo writes a reviewable
 * {@code target/conservation/<name>.conservation.txt} artifact (the
 * see-the-data-first deliverable the query surface will be designed from)
 * and asserts the query outcomes that would gate compilation.
 */
class ConservationReportTest {

    private static final Path OUT = Path.of("target", "conservation");

    private ConservationLedger ledger(String name, String src) throws Exception {
        ConservationReport.writeReport(OUT, name, src, name + ".ptf");
        Result r = ConservationReport.fromAltSource(src, name + ".ptf");
        assertInstanceOf(Result.Generated.class, r,
                () -> "expected ledger; got: " + ((Result.Failed) r).error());
        return ((Result.Generated) r).ledger();
    }

    private ConservationNode node(ConservationLedger ledger, String fn) {
        return ledger.node(fn).orElseThrow(() -> new AssertionError("no node for " + fn));
    }

    // --- 1+2: symbolic translation — the lossless gate ---

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
    void lossyTranslation_failsLossless_andTheLedgerShowsTheDrop() throws Exception {
        ConservationLedger ledger = ledger("translate-lossy", LOSSY);
        ConservationNode translate = node(ledger, "translate");
        assertFalse(ConservationQueries.lossless(translate),
                "s.email never flows into Target — the silent loss must fail the gate");
        // The printed reading names the loss.
        Result r = ConservationReport.fromAltSource(LOSSY, "t.ptf");
        String text = ((Result.Generated) r).text();
        assertTrue(text.contains("s_0.email") && text.contains("UNTOUCHED"),
                () -> "expected the drop to be visible:\n" + text);
    }

    @Test
    void fixedTranslation_passesLossless() throws Exception {
        ConservationNode translate = node(ledger("translate-fixed", FIXED), "translate");
        assertTrue(ConservationQueries.lossless(translate),
                "every Source attribute reaches Target (verbatim or derived)");
    }

    // --- 3: swap — the reversibility witness, no arrays needed ---

    @Test
    void swap_witnessesReversibility() throws Exception {
        String src = """
                function swap(p:[(Int, Bool)]):[(Bool, Int)] ->
                  match p { [(a, b)] -> (b, a) }
                swap((1, true))
                """;
        ConservationNode swap = node(ledger("swap", src), "swap");
        assertTrue(ConservationQueries.verbatimBijection(swap),
                "fan-in-free, fan-out-free verbatim placement — structurally invertible");
        assertTrue(ConservationQueries.lossless(swap));
        assertFalse(ConservationQueries.duplicated(swap));
    }

    // --- 4: branching — consulted-only vs emitted, per branch ---

    @Test
    void clamp_branchQuantifiers() throws Exception {
        String src = """
                function clamp(x:Int):Int -> match x {
                  [@>0] -> x
                  _ -> 0
                }
                clamp(5)
                """;
        ConservationNode clamp = node(ledger("clamp", src), "clamp");
        // The positive branch conserves x; the floor branch only consults it.
        assertFalse(ConservationQueries.lossless(clamp),
                "not every branch carries x's content to the output");
        assertFalse(ConservationQueries.duplicated(clamp));
        assertTrue(ConservationQueries.everyBranch(clamp,
                        b -> ConservationQueries.untouched(clamp, b).isEmpty()),
                "x is touched (emitted or consulted) in every branch — never silently ignored");
    }

    // --- 5: duplication is caught ---

    @Test
    void duplication_breaksTheBijection() throws Exception {
        String src = """
                function dup(p:[(Int, Bool)]):[(Int, Int)] ->
                  match p { [(a, _)] -> (a, a) }
                dup((1, true))
                """;
        ConservationNode dup = node(ledger("dup", src), "dup");
        assertTrue(ConservationQueries.duplicated(dup), "a emitted into both slots");
        assertFalse(ConservationQueries.verbatimBijection(dup));
    }

    // --- 6: opaque honesty — the ledger never over-claims ---

    @Test
    void untraceableFlow_failsClosed() throws Exception {
        // A nested match in value position is untraced in v1 — the ledger says
        // OPAQUE and refuses to certify conservation, rather than guessing.
        String src = """
                function f(x:Int, y:Int):Int ->
                  let inner = match y { [@>0] -> x
                  _ -> 0 }
                  inner + y
                """;
        ConservationLedger ledger = ledger("opaque", src);
        ConservationNode f = node(ledger, "f");
        assertFalse(ConservationQueries.lossless(f),
                "untraceable flow must never pass a conservation assertion");
        String text = ((ConservationReport.Result.Generated)
                ConservationReport.fromAltSource(src, "t.ptf")).text();
        assertTrue(text.contains("OPAQUE"), () -> "expected honest ignorance:\n" + text);
    }
}
