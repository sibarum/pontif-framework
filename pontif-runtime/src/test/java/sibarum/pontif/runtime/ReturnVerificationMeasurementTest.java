package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.AliasResolver;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.AltParser;
import sibarum.pontif.receipts.BuiltinIssuer;
import sibarum.pontif.receipts.Drafter;
import sibarum.pontif.receipts.Node;
import sibarum.pontif.receipts.ReceiptGraph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Measurement — the "#1-A" blast-radius step before flipping on
 * reject-unprovable-returns: if declared return refinements were verified at
 * compile time, which currently-compiling functions would flip to rejected?
 *
 * <p>Uses the receipt-graph engine (Drafter + BuiltinIssuer) — the
 * per-branch, recursion-capable verifier that would actually become the
 * compile gate — not the whole-body {@code FunctionCheck.verifyDefinition}
 * (which can't carry the inductive hypotheses recursion needs).
 *
 * <p>A function is PROVABLE iff every branch of its return obligation
 * discharges, UNPROVABLE if any branch doesn't, NO_REFINEMENT if its return
 * isn't refined (nothing to prove). The verdict is computed by the engine, so
 * this also locks in the engine's current reach as a regression.
 *
 * <p><b>Reading of the blast radius:</b> simple linear / sign / inductive
 * refinements are provable today, and Slice-0 interval multiplication extends
 * that to product magnitude (<em>prod</em>). The UNPROVABLE set splits in two:
 * true-but-hard (<em>isSparse</em> — recourse is a Slice-1 hand-supplied
 * refinement proof) and actually-false (<em>bad</em> — correctly rejected).
 * So enabling rejection is safe for the PROVABLE set; the UNPROVABLE set needs
 * either the proof-supply path wired in, or is a genuine error.
 */
class ReturnVerificationMeasurementTest {

    private static Map<String, String> classify(String altSource) throws Exception {
        IrModule resolved = AliasResolver.resolve(AltParser.parseModule(altSource, "m.ptf"));
        ReceiptGraph graph = Drafter.draft(resolved);
        List<BuiltinIssuer.Attempt> attempts = BuiltinIssuer.attemptAll(graph);
        List<Node> nodes = graph.roots();
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            final int ni = i;
            List<BuiltinIssuer.Attempt> na =
                    attempts.stream().filter(a -> a.nodeIndex() == ni).toList();
            String verdict = na.isEmpty() ? "NO_REFINEMENT"
                    : na.stream().allMatch(BuiltinIssuer.Attempt::discharged) ? "PROVABLE"
                    : "UNPROVABLE";
            out.merge(nodes.get(i).functionName(), verdict, ReturnVerificationMeasurementTest::worse);
        }
        return out;
    }

    /** For an overloaded name spanning several nodes, the worst verdict wins. */
    private static String worse(String a, String b) {
        if (a.equals("UNPROVABLE") || b.equals("UNPROVABLE")) return "UNPROVABLE";
        if (a.equals("PROVABLE") || b.equals("PROVABLE")) return "PROVABLE";
        return "NO_REFINEMENT";
    }

    @Test
    void blastRadius_acrossRepresentativeRefinedReturns() throws Exception {
        String corpus = """
                module m

                function factorial(n:[Int:@>=0]):[Int:@>=1] -> match n {
                  [@==0] -> 1
                  [@>0]  -> n * factorial(n-1)
                }
                function inc(x:[Int:@>=1]):[Int:@>1] -> x + 1
                function square(x:Int):[Int:@>=0] -> x * x
                function prod(x:[Int:@>=2], y:[Int:@>=3]):[Int:@>=6] -> x * y
                function addNonNeg(a:[Int:@>=0], b:[Int:@>=0]):[Int:@>=0] -> a + b
                function isSparse(x:Int):[Int:@>=-16] -> (x-3) * (x+5)
                function bad(x:Int):[Int:@>0] -> x
                """;

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("factorial", "PROVABLE");   // inductive (back-reference IH)
        expected.put("inc",       "PROVABLE");   // linear threshold
        expected.put("square",    "PROVABLE");   // sign (square rule)
        expected.put("prod",      "PROVABLE");   // product magnitude — the Slice-0 win
        expected.put("addNonNeg", "PROVABLE");   // linear
        expected.put("isSparse",  "UNPROVABLE"); // true-but-hard → needs a Slice-1 proof
        expected.put("bad",       "UNPROVABLE"); // genuinely false → correctly rejected

        assertEquals(expected, classify(corpus),
                "engine reach changed — update the blast-radius expectation (and celebrate/investigate)");
    }

    @Test
    void blastRadius_coversEveryCorpusShape() throws Exception {
        // One representative per distinct refined-return *shape* found across
        // the test corpus (survey: thresholds, inductive, dependent value-pins,
        // singletons, bool, union, product-magnitude, the hard polynomial).
        // Note shape != verdict: @>0 is provable WITH a supporting hypothesis
        // (posFromGe1) and unprovable WITHOUT (falsePos).
        String corpus = """
                module m

                function risesAboveOne(x:[Int:@>=1]):[Int:@>1] -> x + 1
                function oneOrMore(x:[Int:@>=0]):[Int:@>=1] -> x + 1
                function nonNeg(x:Int):[Int:@>=0] -> x * x
                function posFromGe1(x:[Int:@>=1]):[Int:@>0] -> x
                function falsePos(x:Int):[Int:@>0] -> x
                function depAdd(a:Int, b:Int):[Int:a+b] -> a + b
                function depDbl(n:Int):[Int:n*2] -> n * 2
                function depSucc(y:Int):[Int:y+1] -> y + 1
                function singZero():[Int:0]
                function singAns():[Int:42]
                function alwaysFalse():[Bool:false]
                function bit():[Int:0|1] -> 0
                function prodMag(x:[Int:@>=2], y:[Int:@>=3]):[Int:@>=6] -> x * y
                function sparse(x:Int):[Int:@>=-16] -> (x-3) * (x+5)
                """;

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("risesAboveOne", "PROVABLE");   // threshold @>1
        expected.put("oneOrMore",     "PROVABLE");   // threshold @>=1
        expected.put("nonNeg",        "PROVABLE");   // sign @>=0
        expected.put("posFromGe1",    "PROVABLE");   // @>0 WITH supporting hyp
        expected.put("falsePos",      "UNPROVABLE"); // @>0 WITHOUT — genuinely false
        expected.put("depAdd",        "PROVABLE");   // dependent value-pin (reflexive)
        expected.put("depDbl",        "PROVABLE");   // dependent
        expected.put("depSucc",       "PROVABLE");   // dependent
        expected.put("singZero",      "PROVABLE");   // singleton (synthesized)
        expected.put("singAns",       "PROVABLE");   // singleton
        expected.put("alwaysFalse",   "PROVABLE");   // [Bool:false] — PREDICT (reflexive)
        expected.put("bit",           "PROVABLE");   // [Int:0|1] — now provable (Or-goal fix)
        expected.put("prodMag",       "PROVABLE");   // product magnitude — Slice 0
        expected.put("sparse",        "UNPROVABLE"); // true-but-hard → needs a proof

        assertEquals(expected, classify(corpus),
                "shape-coverage verdict changed — reconcile the prediction with reality");
    }
}
