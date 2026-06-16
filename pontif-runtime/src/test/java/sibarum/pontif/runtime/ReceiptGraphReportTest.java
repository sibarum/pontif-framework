package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5: end-to-end receipt-graph text artifacts from alt-syntax source.
 * Emits the canonical examples to {@code target/receipt-graphs/} as
 * reviewable build artifacts and asserts the headline content.
 */
class ReceiptGraphReportTest {

    private static final Path OUT = Path.of("target", "receipt-graphs");

    private static String generate(String src, String name) {
        ReceiptGraphReport.Result r = ReceiptGraphReport.fromAltSource(src, name);
        assertInstanceOf(ReceiptGraphReport.Result.Generated.class, r,
                () -> "Expected Generated; got " + r);
        return ((ReceiptGraphReport.Result.Generated) r).text();
    }

    @Test
    void square_emitsGraphAndDischarge() throws Exception {
        String src = """
                module square
                function square(x:Int):[Int:@>=0] -> x * x
                square(3)
                """;
        Path path = ReceiptGraphReport.writeReport(OUT, "square", src, "square.ptf");
        String text = Files.readString(path);
        System.out.println(text);

        assertTrue(text.contains("square(x_0: Int) : r_0: [Int: @ >= 0]"), () -> text);
        assertTrue(text.contains("receipt: r_0 == x_0 * x_0"), () -> text);
        assertTrue(text.contains("square  :  r_0 >= 0"), () -> text);
        assertTrue(text.contains("-> discharged [notary: accepted]"), () -> text);
    }

    @Test
    void tooStrongRefinement_showsNotDischarged() throws Exception {
        // [Int:@>=1] is false for square(0)=0, so the issuer can't discharge
        // it — the report must SAY so rather than go silent.
        String src = """
                module square
                function square(x:Int):[Int:@>=1] -> x * x
                square(3)
                """;
        Path path = ReceiptGraph_writeReport("square_strong", src);
        String text = Files.readString(path);
        System.out.println(text);

        assertTrue(text.contains("square  :  r_0 >= 1"), () -> text);
        assertTrue(text.contains("NOT DISCHARGED"), () -> text);
    }

    private static Path ReceiptGraph_writeReport(String name, String src) throws Exception {
        return ReceiptGraphReport.writeReport(OUT, name, src, name + ".ptf");
    }

    @Test
    void thresholdReturn_dischargesViaLinearBounds() throws Exception {
        // inc adds 1 to an input that's already >= 1, so the result is > 1.
        // Sign analysis only knows POSITIVE (can't clear the > 1 bar); the
        // linear-bound engine normalizes (x_0 + 1) - 1 = x_0 ∈ [1, ∞) and
        // discharges. This is the case the whole numeric-discharge slice is for.
        String src = """
                module inc
                function inc(x:[Int:@>=1]):[Int:@>1] -> x + 1
                inc(4)
                """;
        Path path = ReceiptGraphReport.writeReport(OUT, "inc", src, "inc.ptf");
        String text = Files.readString(path);
        System.out.println(text);

        assertTrue(text.contains("inc(x_0: [Int: @ >= 1]) : r_0: [Int: @ > 1]"), () -> text);
        assertTrue(text.contains("receipt: r_0 == x_0 + 1"), () -> text);
        assertTrue(text.contains("inc  :  r_0 > 1"), () -> text);
        assertTrue(text.contains("-> discharged [notary: accepted]"), () -> text);
    }

    @Test
    void bareIntCallee_callRefSortShowsBodyInferredNarrowing() throws Exception {
        // add5's declared return is bare :Int, but the body x+5 over x:[Int:@>=0]
        // bounds to [Int:@>=5]. The drafter's body-inference fallback (calling
        // NarrowingInference.inferCallReturnFromBody) lifts that into the CallRef
        // result var's sort, so caller's graph reads r_1: [Int: @ >= 5] instead
        // of the bare Int it would have shown before this slice.
        String src = """
                module bareInt
                function add5(x:[Int:@>=0]):Int -> x + 5
                function caller(x:[Int:@>=0]):Int -> add5(x)
                caller(7)
                """;
        Path path = ReceiptGraphReport.writeReport(OUT, "bareIntCallee", src, "bareIntCallee.ptf");
        String text = Files.readString(path);
        System.out.println(text);

        assertTrue(text.contains("call: add5(x_0) -> r_1: [Int: @ >= 5]"),
                () -> "Expected CallRef result sort to reflect body-inferred [Int:@>=5]:\n" + text);
    }

    @Test
    void chainArithmetic_dischargesViaBodyInferredCalleeReturn() throws Exception {
        // The headline payoff: chain's [Int:@>=10] return obligation can only
        // close if add5's CallRef result sort carries r_1 >= 5 into PathFacts.
        // Before this slice, add5's bare :Int return left r_1 unbounded and
        // BoundAnalysis could only show r_0 = r_1 + 5 with r_1 ∈ (-∞, ∞).
        String src = """
                module chain
                function add5(x:[Int:@>=0]):Int -> x + 5
                function chain(x:[Int:@>=0]):[Int:@>=10] -> add5(x) + 5
                chain(5)
                """;
        Path path = ReceiptGraphReport.writeReport(OUT, "chainArithmetic", src, "chainArithmetic.ptf");
        String text = Files.readString(path);
        System.out.println(text);

        assertTrue(text.contains("call: add5(x_0) -> r_1: [Int: @ >= 5]"), () -> text);
        assertTrue(text.contains("receipt: r_0 == r_1 + 5"), () -> text);
        assertTrue(text.contains("chain  :  r_0 >= 10"), () -> text);
        assertTrue(text.contains("-> discharged [notary: accepted]"), () -> text);
        assertTrue(!text.contains("NOT DISCHARGED"),
                () -> "chain's obligation should discharge via body-inferred callee return:\n" + text);
    }

    @Test
    void sign_emitsThreeBranches() throws Exception {
        String src = """
                module sign
                function sign(n:Int):Int -> match n
                  [@<0 ] -> -1
                  [@==0] -> 0
                  [@>0 ] -> 1
                sign(-7)
                """;
        Path path = ReceiptGraphReport.writeReport(OUT, "sign", src, "sign.ptf");
        String text = Files.readString(path);
        System.out.println(text);

        assertTrue(text.contains("branch [n_0 < 0]:"), () -> text);
        assertTrue(text.contains("branch [n_0 == 0]:"), () -> text);
        assertTrue(text.contains("branch [n_0 > 0]:"), () -> text);
        // sign's return is bare Int — no obligation to prove.
        assertTrue(text.contains("sign  (no return refinement -- nothing to prove)"), () -> text);
    }

    @Test
    void factorial_emitsGraphAndClosesBothBranches() throws Exception {
        String src = """
                module factorial
                function factorial(n:[Int:@>=0]):[Int:@>=1] -> match n
                  [@==0] -> 1
                  [@>0 ] -> n * factorial(n-1)
                factorial(5)
                """;
        Path path = ReceiptGraphReport.writeReport(OUT, "factorial", src, "factorial.ptf");
        String text = Files.readString(path);
        System.out.println(text);

        // The graph.
        assertTrue(text.contains("factorial(n_0: [Int: @ >= 0]) : r_0: [Int: @ >= 1]"), () -> text);
        assertTrue(text.contains("call: factorial(n_0 - 1) -> r_1: [Int: @ >= 1]"), () -> text);
        assertTrue(text.contains("receipt: r_0 == n_0 * r_1"), () -> text);

        // Both branches discharge r_0 >= 1, both accepted by the notary.
        // The hypotheses + goal lines surface the actual reasoning: branch 1
        // closes because the back-reference brings r_1 >= 1 into scope, and
        // the substituted goal n_0 * r_1 >= 1 closes via POSITIVE × POSITIVE.
        assertTrue(text.contains("factorial  :  r_0 >= 1"), () -> text);
        assertTrue(text.contains("branch 0 [n_0 == 0]\n"), () -> text);
        assertTrue(text.contains("hypotheses: n_0 >= 0, n_0 == 0"), () -> text);
        assertTrue(text.contains("goal: 1 >= 1"), () -> text);
        assertTrue(text.contains("branch 1 [n_0 > 0]\n"), () -> text);
        assertTrue(text.contains("hypotheses: n_0 >= 0, n_0 > 0, r_1 >= 1"), () -> text);
        assertTrue(text.contains("goal: n_0 * r_1 >= 1"), () -> text);
    }

    @Test
    void ackermann_dischargesGreaterThanOneOnAllThreeOverloads() throws Exception {
        // The headline payoff: Ackermann with a [Int:@>1] postcondition on
        // every overload closes cleanly. Branch 0 (y_0 + 1 > 1 from y_0 > 0)
        // is the BoundAnalysis win sign analysis couldn't do; the recursive
        // branches close because each CallRef's result sort [Int:@>1] is the
        // inductive hypothesis the back-reference carries into scope.
        String src = """
                module ackermann
                function ackermann(x:[Int:@==0], y:[Int:@>0]):[Int:@>1] -> y + 1
                function ackermann(x:[Int:@>0], y:[Int:@==0]):[Int:@>1] -> ackermann(x - 1, 1)
                function ackermann(x:[Int:@>0], y:[Int:@>0]):[Int:@>1] -> ackermann(x - 1, ackermann(x, y - 1))
                ackermann(2, 2)
                """;
        Path path = ReceiptGraphReport.writeReport(OUT, "ackermann", src, "ackermann.ptf");
        String text = Files.readString(path);
        System.out.println(text);

        // Branch 0's obligation is the threshold sign analysis couldn't clear.
        assertTrue(text.contains("ackermann(x_0: [Int: @ == 0], y_0: [Int: @ > 0]) : r_0: [Int: @ > 1]"),
                () -> text);
        // The recursive call result sorts carry the [Int:@>1] inductive hypothesis.
        assertTrue(text.contains("call: ackermann(x_0 - 1, r_1) -> r_2: [Int: @ > 1]"), () -> text);
        // All three overloads discharge; nothing left NOT DISCHARGED.
        assertEquals(3, countOccurrences(text, "-> discharged [notary: accepted]"),
                () -> "Expected all three overloads to discharge:\n" + text);
        assertTrue(!text.contains("NOT DISCHARGED"), () -> text);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    void handWrittenProof_showsDischargedViaProof() throws Exception {
        // quirk = (x-3)*(x+5) >= -16 is beyond the built-in engine (opaque product
        // with an interior minimum), but a hand-written split closes it (the middle
        // [-5,2] auto-peels). The report must agree with the gate — render the
        // branch as discharged [via proof], not NOT DISCHARGED.
        String src = """
                module quirk
                struct Leaf()
                struct Split(p:Bool, whenTrue:[Leaf|Split], whenFalse:[Leaf|Split])
                function quirk(x:Int):[Int:@>=-16] -> (x - 3) * (x + 5)
                proof quirk = Split(x>=3, Leaf(), Split(x<=-6, Leaf(), Leaf()))
                quirk(5)
                """;
        Path path = ReceiptGraphReport.writeReport(OUT, "quirkProof", src, "quirkProof.ptf");
        String text = Files.readString(path);
        System.out.println(text);

        assertTrue(text.contains("quirk  :  r_0 >= -16"), () -> text);
        assertTrue(text.contains("-> discharged [via proof; notary: accepted]"),
                () -> "quirk's obligation should render as proof-discharged:\n" + text);
        assertTrue(!text.contains("NOT DISCHARGED"),
                () -> "the supplied proof should close quirk in the report:\n" + text);
    }

    @Test
    void importedProofTypes_reportAgreesWithRun() throws Exception {
        // Regression: when the proof vocabulary is imported from the builtin
        // std.proof module (the playground default), the report must LINK first
        // — same as Run — or the proof tree stays unresolved and quirk falsely
        // renders NOT DISCHARGED even though the runtime executes it. This is the
        // "Receipts say not discharged, runtime runs happily" bug.
        String src = """
                module tour
                requires std.proof.{Leaf, Split}
                function quirk(x:Int):[Int:@>=-16] -> (x - 3) * (x + 5)
                proof quirk = Split(x>=3, Leaf(), Split(x<=-6, Leaf(), Leaf()))
                quirk(5)
                """;
        String text = generate(src, "tour.ptf");
        assertTrue(text.contains("-> discharged [via proof; notary: accepted]"),
                () -> "imported-proof quirk should render as proof-discharged:\n" + text);
        assertTrue(!text.contains("NOT DISCHARGED"),
                () -> "the imported proof should close quirk in the report:\n" + text);
    }

    @Test
    void withoutProof_quirkShowsNotDischarged() throws Exception {
        // The same program minus the proof: the report honestly shows NOT
        // DISCHARGED — confirming the proof is what flips it and the report
        // tracks the gate's view rather than going silent.
        String src = """
                module quirk
                function quirk(x:Int):[Int:@>=-16] -> (x - 3) * (x + 5)
                quirk(5)
                """;
        Path path = ReceiptGraphReport.writeReport(OUT, "quirkNoProof", src, "quirkNoProof.ptf");
        String text = Files.readString(path);
        assertTrue(text.contains("NOT DISCHARGED"), () -> text);
    }

    @Test
    void parseError_writesFailureArtifact() throws Exception {
        String src = "module broken\nfunction (((";
        Path path = ReceiptGraphReport.writeReport(OUT, "broken", src, "broken.ptf");
        String text = Files.readString(path);
        assertTrue(text.contains("error"), () -> "Failure artifact should record the error:\n" + text);
    }

    @Test
    void matchOnCallScrutinee_buildsGraph() {
        // A `match someCall()` desugars to `let __s = someCall() in match __s`;
        // the drafter must see through that wrapper. Without it, the embedded
        // match reaches the SymExpr kernel ("Match inside refinement predicate")
        // and aborts the WHOLE draft — the receipt view goes blank for the file.
        String src = """
                module m
                struct P(a:Int, b:Int)
                method P.id():P -> P(this.a, this.b)
                function f(p:P):Int -> match p.id()
                  [P(x, y)] -> x + y
                f(P(1, 2))
                """;
        String text = generate(src, "match-on-call.ptf");
        assertTrue(text.contains("f("), () -> text);
    }
}
