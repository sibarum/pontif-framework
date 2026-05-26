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
 * R2 drafter slice: {@code match} bodies become one {@link Branch} per
 * arm, each with its guard ({@code @} bound to the scrutinee) and body
 * equation.
 */
class DrafterMatchTest {

    /** Refinement pattern {@code [Int:@ op lit]}. */
    private static IrSort armPattern(IrExpr.Op op, long lit) {
        return IrSort.refined("Int", IrExpr.binOp(op, IrExpr.self(), IrExpr.lit(lit)));
    }

    private static IrModule signModule() {
        // sign(n:Int):Int -> match n { [@<0]->-1; [@==0]->0; [@>0]->1 }
        return new IrModule("m",
                List.of(IrStmt.functionDecl(
                        "sign",
                        List.of(new IrParam("n", IrSort.named("Int"))),
                        IrSort.named("Int"),
                        IrExpr.match(
                                IrExpr.var("n"),
                                List.of(
                                        IrExpr.matchBranch(armPattern(IrExpr.Op.LT, 0), IrExpr.lit(-1)),
                                        IrExpr.matchBranch(armPattern(IrExpr.Op.EQ, 0), IrExpr.lit(0)),
                                        IrExpr.matchBranch(armPattern(IrExpr.Op.GT, 0), IrExpr.lit(1)))))),
                IrExpr.lit(0));
    }

    @Test
    void draftsOneBranchPerArm_withGuardsBoundToScrutinee() throws Exception {
        ReceiptGraph graph = Drafter.draft(signModule());
        Node root = graph.roots().get("sign");

        assertEquals(3, root.branches().size(), "one branch per arm");

        // Arm 0: guard n_0 < 0, receipt r_0 == -1
        Branch arm0 = root.branches().get(0);
        assertEquals(
                SymExpr.cmp(SymExpr.var("n_0"), SymExpr.CmpOp.LT, SymExpr.lit(0)),
                arm0.guard().orElseThrow());
        assertEquals(
                SymExpr.cmp(SymExpr.var("r_0"), SymExpr.CmpOp.EQ, SymExpr.lit(-1)),
                arm0.initialReceipts().get(0).claim());
        assertTrue(arm0.calls().isEmpty());

        // Arm 1: guard n_0 == 0, receipt r_0 == 0
        Branch arm1 = root.branches().get(1);
        assertEquals(
                SymExpr.cmp(SymExpr.var("n_0"), SymExpr.CmpOp.EQ, SymExpr.lit(0)),
                arm1.guard().orElseThrow());
        assertEquals(
                SymExpr.cmp(SymExpr.var("r_0"), SymExpr.CmpOp.EQ, SymExpr.lit(0)),
                arm1.initialReceipts().get(0).claim());

        // Arm 2: guard n_0 > 0, receipt r_0 == 1
        Branch arm2 = root.branches().get(2);
        assertEquals(
                SymExpr.cmp(SymExpr.var("n_0"), SymExpr.CmpOp.GT, SymExpr.lit(0)),
                arm2.guard().orElseThrow());
        assertEquals(
                SymExpr.cmp(SymExpr.var("r_0"), SymExpr.CmpOp.EQ, SymExpr.lit(1)),
                arm2.initialReceipts().get(0).claim());
    }

    @Test
    void renderedGraph_readsLikeTheSource() throws Exception {
        ReceiptGraph graph = Drafter.draft(signModule());
        String text = ReceiptGraphPrinter.print(graph);
        System.out.println(text);

        assertTrue(text.contains("branch [n_0 < 0]:"), () -> text);
        assertTrue(text.contains("receipt: r_0 == -1"), () -> text);
        assertTrue(text.contains("branch [n_0 == 0]:"), () -> text);
        assertTrue(text.contains("branch [n_0 > 0]:"), () -> text);
        assertTrue(text.contains("receipt: r_0 == 1"), () -> text);
    }

    @Test
    void skeletonMatch_holdsForMatchBody() throws Exception {
        // A match-bodied graph still round-trips through skeleton match.
        IrModule module = signModule();
        ReceiptGraph graph = Drafter.draft(module);
        assertTrue(Notary.skeletonMatches(graph, module));
    }

    @Test
    void guardBindsScrutineeExpression_notJustVars() throws Exception {
        // match (n + 1) { [@>0] -> 1; [@<=0] -> 0 } — scrutinee is a BinOp,
        // so @ binds to (n_0 + 1).
        IrModule module = new IrModule("m",
                List.of(IrStmt.functionDecl(
                        "f",
                        List.of(new IrParam("n", IrSort.named("Int"))),
                        IrSort.named("Int"),
                        IrExpr.match(
                                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("n"), IrExpr.lit(1)),
                                List.of(
                                        IrExpr.matchBranch(armPattern(IrExpr.Op.GT, 0), IrExpr.lit(1)),
                                        IrExpr.matchBranch(armPattern(IrExpr.Op.LE, 0), IrExpr.lit(0)))))),
                IrExpr.lit(0));

        ReceiptGraph graph = Drafter.draft(module);
        Branch arm0 = graph.roots().get("f").branches().get(0);

        // guard: (n_0 + 1) > 0  — represented as Cmp(Add(n_0, 1), GT, 0)
        SymExpr expectedGuard = SymExpr.cmp(
                SymExpr.add(SymExpr.var("n_0"), SymExpr.lit(1)),
                SymExpr.CmpOp.GT, SymExpr.lit(0));
        assertEquals(expectedGuard, arm0.guard().orElseThrow());
    }

    @Test
    void nonMatchBody_stillSingleUnconditionalBranch() throws Exception {
        // Regression: the existing non-match path is unchanged.
        IrModule module = new IrModule("m",
                List.of(IrStmt.functionDecl(
                        "double",
                        List.of(new IrParam("n", IrSort.named("Int"))),
                        IrSort.named("Int"),
                        IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("n"), IrExpr.var("n")))),
                IrExpr.lit(0));

        Node root = Drafter.draft(module).roots().get("double");
        assertEquals(1, root.branches().size());
        assertFalse(root.branches().get(0).guard().isPresent());
    }
}
