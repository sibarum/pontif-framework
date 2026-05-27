package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

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
        assertTrue(text.contains("factorial  :  r_0 >= 1"), () -> text);
        assertTrue(text.contains("branch 0 [n_0 == 0]  -> discharged [notary: accepted]"), () -> text);
        assertTrue(text.contains("branch 1 [n_0 > 0]  -> discharged [notary: accepted]"), () -> text);
    }

    @Test
    void parseError_writesFailureArtifact() throws Exception {
        String src = "module broken\nfunction (((";
        Path path = ReceiptGraphReport.writeReport(OUT, "broken", src, "broken.ptf");
        String text = Files.readString(path);
        assertTrue(text.contains("error"), () -> "Failure artifact should record the error:\n" + text);
    }
}
