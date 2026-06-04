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
 * Division/remainder in BODY position hoist like calls (under dispatch
 * unification they are operator calls): a fresh result var with an
 * UNREFINED sort, so the body equation transcribes, the receipts claim
 * nothing about the divided value, and no inductive hypothesis can attach.
 * The predicate-position fence is untouched — the kernel stays linear.
 * Born from ternion.ptf, whose {@code 1/n} killed the whole receipt-graph
 * report.
 */
class DrafterDivisionTest {

    private static IrModule moduleWith(String name, IrParam param, IrSort ret, IrExpr body) {
        return new IrModule("m",
                List.of(IrStmt.functionDecl(name, List.of(param), ret, body)),
                IrExpr.lit(0));
    }

    @Test
    void divisionInBody_hoistsAsOperatorCall_withUnrefinedResult() throws Exception {
        // half(x:Int):Int -> x / 2
        IrModule m = moduleWith("half",
                new IrParam("x", IrSort.named("Int")), IrSort.named("Int"),
                IrExpr.binOp(IrExpr.Op.DIV, IrExpr.var("x"), IrExpr.lit(2)));
        ReceiptGraph graph = Drafter.draft(m);
        Node half = graph.roots().get(0);
        Branch branch = half.branches().get(0);

        assertEquals(1, branch.calls().size(), "the division hoists as one call");
        CallRef div = branch.calls().get(0);
        assertEquals("/", div.targetFunctionName());
        assertEquals(2, div.argBindings().size());
        assertFalse(div.resultVar().sort().isRefined(),
                "no narrowing may attach — a refined sort would smuggle in a false IH");
        // The body equation references the hoisted var: r_0 == r_1.
        SymExpr receipt = branch.initialReceipts().get(0).claim();
        assertTrue(ReceiptGraphPrinter.renderSym(receipt)
                        .contains(div.resultVar().name()),
                () -> ReceiptGraphPrinter.renderSym(receipt));
    }

    @Test
    void remainder_hoistsAsPercentCall() throws Exception {
        IrModule m = moduleWith("rem",
                new IrParam("x", IrSort.named("Int")), IrSort.named("Int"),
                IrExpr.binOp(IrExpr.Op.MOD, IrExpr.var("x"), IrExpr.lit(3)));
        Branch branch = Drafter.draft(m).roots().get(0).branches().get(0);
        assertEquals("%", branch.calls().get(0).targetFunctionName());
    }

    @Test
    void decimalOperand_promotesTheResultVarBase() throws Exception {
        // half(x:Decimal):Decimal -> x / 2 — params are in the type env, so
        // the promotion ruling (any Decimal operand -> Decimal) applies.
        IrModule m = moduleWith("half",
                new IrParam("x", IrSort.named("Decimal")), IrSort.named("Decimal"),
                IrExpr.binOp(IrExpr.Op.DIV, IrExpr.var("x"), IrExpr.lit(2)));
        Branch branch = Drafter.draft(m).roots().get(0).branches().get(0);
        assertEquals("Decimal", branch.calls().get(0).resultVar().sort().name());
    }

    @Test
    void divisionNestedInArithmetic_keepsTheSurroundingEquation() throws Exception {
        // f(x:Int):Int -> 1 + x / 2 — the rest of the body keeps its receipt;
        // only the divided value is opaque.
        IrModule m = moduleWith("f",
                new IrParam("x", IrSort.named("Int")), IrSort.named("Int"),
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.lit(1),
                        IrExpr.binOp(IrExpr.Op.DIV, IrExpr.var("x"), IrExpr.lit(2))));
        Branch branch = Drafter.draft(m).roots().get(0).branches().get(0);
        String receipt = ReceiptGraphPrinter.renderSym(
                branch.initialReceipts().get(0).claim());
        assertTrue(receipt.contains("1 +"),
                () -> "the addition must survive around the hoisted var: " + receipt);
    }
}
