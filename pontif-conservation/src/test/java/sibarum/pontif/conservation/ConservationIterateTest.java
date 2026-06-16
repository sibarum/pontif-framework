package sibarum.pontif.conservation;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrParam;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bounded fold is no longer opaque to the conservation drafter
 * (docs/iteration.md §4): it generates ledger entries — a Branch that consults
 * the source and discriminates on the element, each arm a Construction placing
 * the element into named output streams. (The old slice-1 throw is gone.)
 */
class ConservationIterateTest {

    private static IrSort armPattern(IrExpr.Op op, long lit) {
        return IrSort.refined("Int", IrExpr.binOp(op, IrExpr.self(), IrExpr.lit(lit)));
    }

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
    void iteration_draftsBranchLedger_withoutThrowing() throws Exception {
        ConservationGraph.Ledger ledger = ConservationDrafter.draft(classifyModule());
        ConservationGraph graph = ledger.graphs().stream()
                .filter(g -> g.functionName().equals("classify"))
                .findFirst().orElseThrow();

        // The per-frame fold is a Branch (consult source, discriminate element).
        assertTrue(graph.nodes().values().stream()
                        .anyMatch(n -> n instanceof FlowNode.Branch),
                "the iteration drafts a Branch node — it is not opaque");
        // And per-arm placements are Constructions (the element placed into outputs).
        assertFalse(graph.nodes().values().stream()
                        .noneMatch(n -> n instanceof FlowNode.Construction),
                "each arm places the element into a Construction");
    }
}
