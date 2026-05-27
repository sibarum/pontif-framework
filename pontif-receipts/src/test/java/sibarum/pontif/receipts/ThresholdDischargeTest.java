package sibarum.pontif.receipts;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrParam;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The linear-bound engine's headline: an integer <em>threshold</em> return
 * refinement that {@code SignAnalysis} alone can't discharge. {@code inc}
 * adds 1 to an input that's already {@code >= 1}, so the result is
 * {@code > 1} — but sign analysis only knows POSITIVE, which can't clear
 * the {@code > 1} bar. {@link BoundAnalysis} normalizes to
 * {@code (x_0 + 1) - 1 = x_0 ∈ [1, ∞)} and discharges it.
 */
class ThresholdDischargeTest {

    private static IrSort intGe(long n) {
        return IrSort.refined("Int", IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(n)));
    }

    private static IrSort intGt(long n) {
        return IrSort.refined("Int", IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(n)));
    }

    /** {@code function inc(x:[Int:@>=1]):[Int:@>1] -> x + 1} */
    private static IrModule incModule() {
        IrExpr body = IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.lit(1));
        return new IrModule("m",
                List.of(IrStmt.functionDecl("inc",
                        List.of(new IrParam("x", intGe(1))), intGt(1), body)),
                IrExpr.lit(0));
    }

    @Test
    void inc_dischargesThresholdReturn() throws Exception {
        ReceiptGraph graph = Drafter.draft(incModule());
        List<ClosingReceipt> receipts = BuiltinIssuer.close(graph);

        assertEquals(1, receipts.size(),
                () -> "Expected the single unconditional branch to discharge r_0 > 1; got "
                        + receipts.size() + "\n" + ReceiptGraphPrinter.print(graph));

        ClosingReceipt r = receipts.get(0);
        assertEquals("inc", graph.roots().get(r.reference().nodeIndex()).functionName());
        assertEquals("r_0 > 1", ReceiptGraphPrinter.renderSym(r.conclusion()),
                () -> "Unexpected conclusion: " + ReceiptGraphPrinter.renderSym(r.conclusion()));
    }

    @Test
    void inc_notaryAcceptsTheClosingReceipt() throws Exception {
        ReceiptGraph graph = Drafter.draft(incModule());
        for (ClosingReceipt r : BuiltinIssuer.close(graph)) {
            Notary.Verdict v = Notary.hypothesisSupported(graph, r);
            assertTrue(v.accepted(),
                    () -> "Notary should accept inc's r_0 > 1; got: " + v.reason());
        }
    }

    @Test
    void notary_refutesFalseThresholdConclusion() throws Exception {
        // x_0 >= 1, r_0 = x_0 + 1  ⊢  r_0 >= 2, contradicting a bogus r_0 <= 1.
        // (r_0 > 5 would NOT be refuted — it's consistent with r_0 >= 2; the
        // notary refutes only what the facts contradict, not everything false.)
        ReceiptGraph graph = Drafter.draft(incModule());
        SymExpr bogus = SymExpr.cmp(SymExpr.var("r_0"), SymExpr.CmpOp.LE, SymExpr.lit(1));
        ClosingReceipt fake = new ClosingReceipt(
                "<malicious>", bogus, new GraphReference(0, 0), java.util.Map.of());

        Notary.Verdict v = Notary.hypothesisSupported(graph, fake);
        assertFalse(v.accepted(),
                () -> "r_0 <= 1 must be refuted (x_0 + 1 >= 2); got: " + v.reason());
    }
}
