package sibarum.pontif.receipts;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrParam;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R1 vertical slice: the full drafter → issuer → notary loop on
 * {@code square(x:Int):[Int:@>=0] -> x*x}. The simplest genuinely-
 * provable obligation ({@code x*x >= 0} via sign analysis), using the
 * existing non-recursive drafter unchanged.
 */
class R1EndToEndTest {

    /** {@code square(x:Int):[Int:@>=0] -> x * x} */
    private static IrModule squareModule() {
        IrSort nonNeg = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(0)));
        return new IrModule("m",
                List.of(IrStmt.functionDecl(
                        "square",
                        List.of(new IrParam("x", IrSort.named("Int"))),
                        nonNeg,
                        IrExpr.binOp(IrExpr.Op.MUL, IrExpr.var("x"), IrExpr.var("x")))),
                IrExpr.lit(0));
    }

    @Test
    void fullLoop_square_dischargesAndVerifies() throws Exception {
        IrModule module = squareModule();

        // 1. Drafter produces the graph.
        ReceiptGraph graph = Drafter.draft(module);
        assertTrue(Notary.graphExists(graph), "graph should exist");

        // 2. Skeleton matches a fresh draft from the same source.
        assertTrue(Notary.skeletonMatches(graph, module), "skeleton should match a fresh draft");

        // 3. Default issuer discharges the r_0 >= 0 obligation.
        List<ClosingReceipt> receipts = BuiltinIssuer.close(graph);
        assertEquals(1, receipts.size(),
                () -> "Expected one closing receipt; got " + receipts.size()
                        + "\nGraph:\n" + ReceiptGraphPrinter.print(graph));
        ClosingReceipt receipt = receipts.get(0);
        assertEquals(BuiltinIssuer.ISSUER_ID, receipt.issuer());
        assertEquals("square", receipt.reference().functionName());
        assertEquals(0, receipt.reference().branchIndex());

        // 4. Notary verifies the closing receipt: not refuted → accepted.
        Notary.Verdict verdict = Notary.hypothesisSupported(graph, receipt);
        assertTrue(verdict.accepted(),
                () -> "Square's r_0 >= 0 should be accepted; got: " + verdict.reason());
    }

    @Test
    void notary_refutesFalseConclusion() {
        // Hand a deliberately-false closing receipt: r_0 < 0 for square.
        // The path facts (r_0 == x_0*x_0) entail r_0 >= 0, refuting r_0 < 0.
        ReceiptGraph graph = draftSquare();
        SymExpr falseConclusion = SymExpr.cmp(SymExpr.var("r_0"), SymExpr.CmpOp.LT, SymExpr.lit(0));
        ClosingReceipt bogus = new ClosingReceipt(
                "<malicious>", falseConclusion,
                new GraphReference("square", 0), Map.of());

        Notary.Verdict verdict = Notary.hypothesisSupported(graph, bogus);
        assertFalse(verdict.accepted(),
                () -> "r_0 < 0 should be refuted for square; got: " + verdict.reason());
    }

    @Test
    void notary_acceptsUnrefutableSnakeOil() {
        // r_0 == 0: not refutable (x_0*x_0 can be 0), not independently
        // derivable either. The notary accepts (fails to refute) — this is
        // the snake-oil case (would be flagged at a higher layer).
        ReceiptGraph graph = draftSquare();
        SymExpr snakeOil = SymExpr.cmp(SymExpr.var("r_0"), SymExpr.CmpOp.EQ, SymExpr.lit(0));
        ClosingReceipt receipt = new ClosingReceipt(
                "<somebody>", snakeOil,
                new GraphReference("square", 0), Map.of());

        Notary.Verdict verdict = Notary.hypothesisSupported(graph, receipt);
        assertTrue(verdict.accepted(),
                () -> "r_0 == 0 isn't refutable, so notary accepts; got: " + verdict.reason());
    }

    @Test
    void skeletonMatch_rejectsTamperedGraph() throws Exception {
        // A graph for `square` checked against a DIFFERENT source (cube)
        // should fail skeleton match.
        ReceiptGraph squareGraph = draftSquare();
        IrSort nonNeg = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(0)));
        IrModule cube = new IrModule("m",
                List.of(IrStmt.functionDecl(
                        "square",  // same name, different body
                        List.of(new IrParam("x", IrSort.named("Int"))),
                        nonNeg,
                        IrExpr.binOp(IrExpr.Op.MUL, IrExpr.var("x"),
                                IrExpr.binOp(IrExpr.Op.MUL, IrExpr.var("x"), IrExpr.var("x"))))),
                IrExpr.lit(0));

        assertFalse(Notary.skeletonMatches(squareGraph, cube),
                "graph drafted from x*x must not match a fresh draft of x*x*x");
    }

    @Test
    void issuer_emitsNothingForUnrefinedReturn() throws Exception {
        // double(n:Int):Int -> n + n — no refinement, no obligation.
        IrModule module = new IrModule("m",
                List.of(IrStmt.functionDecl(
                        "double",
                        List.of(new IrParam("n", IrSort.named("Int"))),
                        IrSort.named("Int"),
                        IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("n"), IrExpr.var("n")))),
                IrExpr.lit(0));

        ReceiptGraph graph = Drafter.draft(module);
        assertTrue(BuiltinIssuer.close(graph).isEmpty(),
                "no refined return → nothing to discharge");
    }

    private static ReceiptGraph draftSquare() {
        try {
            return Drafter.draft(squareModule());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
