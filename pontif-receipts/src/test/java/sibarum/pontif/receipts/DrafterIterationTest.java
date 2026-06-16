package sibarum.pontif.receipts;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;
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
 * The bounded fold is drafted as a named per-frame step Node
 * {@code <fn>$iter$<k>} (docs/iteration.md §5/§6): the enclosing function hoists
 * the iteration like a call (a CallRef to the step), and the step is one Branch
 * per arm with the element pattern as guard — reusing the recursion machinery,
 * so the artifact renders it as a distinct, proof-targetable unit.
 */
class DrafterIterationTest {

    private static IrSort armPattern(IrExpr.Op op, long lit) {
        return IrSort.refined("Int", IrExpr.binOp(op, IrExpr.self(), IrExpr.lit(lit)));
    }

    /**
     * classify(xs):... -> iter(xs) { [@>0] -> accept(e); [@<=0] -> reject(e) }
     */
    private static IrModule classifyModule() {
        IrExpr.Iterate it = new IrExpr.Iterate(
                IrExpr.var("xs"), "e",
                List.of(new IrExpr.OutputSpec("accept", IrExpr.OutputKind.STREAM, null),
                        new IrExpr.OutputSpec("reject", IrExpr.OutputKind.STREAM, null)),
                List.of(
                        new IrExpr.Arm(armPattern(IrExpr.Op.GT, 0),
                                List.of(new IrExpr.Write("accept", null, IrExpr.var("e")))),
                        new IrExpr.Arm(armPattern(IrExpr.Op.LE, 0),
                                List.of(new IrExpr.Write("reject", null, IrExpr.var("e"))))),
                Origin.NONE);
        return new IrModule("m",
                List.of(IrStmt.functionDecl(
                        "classify",
                        List.of(new IrParam("xs", IrSort.named("Stream"))),
                        IrSort.named("Stream"),
                        it)),
                IrExpr.lit(0));
    }

    @Test
    void iteration_draftsNamedStepNode_oneBranchPerArm() throws Exception {
        ReceiptGraph graph = Drafter.draft(classifyModule());

        // The enclosing function hoists the iteration into a CallRef to the step.
        Node classify = graph.nodesNamed("classify").get(0);
        assertEquals(1, classify.branches().size());
        List<CallRef> calls = classify.branches().get(0).calls();
        assertEquals(1, calls.size(), "the iteration is hoisted as one call");
        assertEquals("classify$iter$0", calls.get(0).targetFunctionName());

        // The step Node is a sibling root: one branch per arm, element-keyed guards.
        Node step = graph.nodesNamed("classify$iter$0").get(0);
        assertEquals(2, step.branches().size());

        Branch accept = step.branches().get(0);
        assertTrue(accept.guard().isPresent(), "the arm pattern is the branch guard");
        assertEquals(
                SymExpr.cmp(SymExpr.var("e_0"), SymExpr.CmpOp.GT, SymExpr.lit(0)),
                accept.guard().get());
        // The placed value (the element, verbatim) is the per-frame body equation.
        assertEquals(
                SymExpr.cmp(SymExpr.var("r_0"), SymExpr.CmpOp.EQ, SymExpr.var("e_0")),
                accept.initialReceipts().get(0).claim());

        Branch reject = step.branches().get(1);
        assertEquals(
                SymExpr.cmp(SymExpr.var("e_0"), SymExpr.CmpOp.LE, SymExpr.lit(0)),
                reject.guard().get());
    }

    @Test
    void iteration_doesNotThrow_andStaysOutOfTheLinearKernel() throws Exception {
        // The Iterate never reaches compileSymExpr (it is hoisted first), so a
        // function whose whole body is an iteration drafts without the old throw.
        ReceiptGraph graph = Drafter.draft(classifyModule());
        assertFalse(graph.nodesNamed("classify$iter$0").isEmpty());
    }
}
