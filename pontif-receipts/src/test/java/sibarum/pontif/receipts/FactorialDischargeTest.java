package sibarum.pontif.receipts;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrParam;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4: with the integer-strictness bridge in {@link IntegerDischarge}, the
 * trivial issuer discharges {@code factorial(n) >= 1} on <em>both</em>
 * branches — base ({@code 1 >= 1}) and recursive ({@code n_0 * r_1 >= 1}
 * under the back-reference IH {@code r_1 >= 1}). The induction was always
 * handled by the graph; the bridge supplies the missing leaf step
 * "for integers, positive means {@code >= 1}".
 */
class FactorialDischargeTest {

    private static IrSort intGe(long n) {
        return IrSort.refined("Int", IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(n)));
    }

    private static IrSort arm(IrExpr.Op op, long lit) {
        return IrSort.refined("Int", IrExpr.binOp(op, IrExpr.self(), IrExpr.lit(lit)));
    }

    private static IrModule factorialModule() {
        IrExpr recursive = IrExpr.binOp(IrExpr.Op.MUL, IrExpr.var("n"),
                IrExpr.call("factorial",
                        List.of(IrExpr.binOp(IrExpr.Op.SUB, IrExpr.var("n"), IrExpr.lit(1)))));
        return new IrModule("m",
                List.of(IrStmt.functionDecl("factorial",
                        List.of(new IrParam("n", intGe(0))), intGe(1),
                        IrExpr.match(IrExpr.var("n"), List.of(
                                IrExpr.matchBranch(arm(IrExpr.Op.EQ, 0), IrExpr.lit(1)),
                                IrExpr.matchBranch(arm(IrExpr.Op.GT, 0), recursive))))),
                IrExpr.lit(0));
    }

    @Test
    void factorial_dischargesBothBranches() throws Exception {
        ReceiptGraph graph = Drafter.draft(factorialModule());
        List<ClosingReceipt> receipts = BuiltinIssuer.close(graph);

        // Both the base [n_0==0] and recursive [n_0>0] arms close r_0 >= 1.
        assertEquals(2, receipts.size(),
                () -> "Expected both branches to discharge; got " + receipts.size()
                        + "\n" + ReceiptGraphPrinter.print(graph));

        // Every receipt references factorial and concludes r_0 >= 1.
        for (ClosingReceipt r : receipts) {
            assertEquals("factorial",
                    graph.roots().get(r.reference().nodeIndex()).functionName());
            assertEquals(
                    "r_0 >= 1",
                    ReceiptGraphPrinter.renderSym(r.conclusion()),
                    () -> "Unexpected conclusion: "
                            + ReceiptGraphPrinter.renderSym(r.conclusion()));
        }

        // Branches 0 and 1 are both covered.
        assertTrue(receipts.stream().anyMatch(r -> r.reference().branchIndex() == 0),
                "base arm discharged");
        assertTrue(receipts.stream().anyMatch(r -> r.reference().branchIndex() == 1),
                "recursive arm discharged");
    }

    @Test
    void factorial_notaryAcceptsBothClosingReceipts() throws Exception {
        ReceiptGraph graph = Drafter.draft(factorialModule());
        for (ClosingReceipt r : BuiltinIssuer.close(graph)) {
            Notary.Verdict v = Notary.hypothesisSupported(graph, r);
            assertTrue(v.accepted(),
                    () -> "Notary should accept branch " + r.reference().branchIndex()
                            + "; got: " + v.reason());
        }
    }

    @Test
    void notary_stillRefutesFalseFactorialConclusion() throws Exception {
        // A bogus r_0 <= 0 on the recursive branch must be refuted:
        // n_0 > 0, r_0 = n_0*r_1, r_1 >= 1  ⊢  r_0 >= 1, contradicting <= 0.
        ReceiptGraph graph = Drafter.draft(factorialModule());
        sibarum.pontif.core.symbolic.SymExpr bogus = sibarum.pontif.core.symbolic.SymExpr.cmp(
                sibarum.pontif.core.symbolic.SymExpr.var("r_0"),
                sibarum.pontif.core.symbolic.SymExpr.CmpOp.LE,
                sibarum.pontif.core.symbolic.SymExpr.lit(0));
        // factorial is the single (node 0); branch 1 is the recursive arm.
        ClosingReceipt fake = new ClosingReceipt(
                "<malicious>", bogus, new GraphReference(0, 1), java.util.Map.of());

        Notary.Verdict v = Notary.hypothesisSupported(graph, fake);
        assertTrue(!v.accepted(),
                () -> "r_0 <= 0 must be refuted on the recursive branch; got: " + v.reason());
    }
}
