package sibarum.pontif.receipts;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrParam;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DrafterTest {

    /**
     * First-slice scope: a non-recursive arithmetic body. Confirms the
     * receipt-graph shape (one root, one unconditional branch, one initial
     * receipt) and that parameter references in the body get renamed to
     * their call-instance form ({@code n} → {@code n_0}).
     */
    @Test
    void drafts_non_recursive_arithmetic_body() throws Exception {
        // function double(n:Int):Int -> n + n
        IrModule module = new IrModule(
                "test",
                List.of(IrStmt.functionDecl(
                        "double",
                        List.of(new IrParam("n", IrSort.named("Int"))),
                        IrSort.named("Int"),
                        IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("n"), IrExpr.var("n")))),
                IrExpr.lit(0));

        ReceiptGraph graph = Drafter.draft(module);

        // One root, keyed by function name.
        assertEquals(1, graph.roots().size());
        Node root = graph.roots().get("double");
        assertNotNull(root);
        assertEquals("double", root.functionName());

        // One param: n_0 : Int.
        assertEquals(1, root.params().size());
        Param param = root.params().get(0);
        assertEquals("n_0", param.name());
        assertEquals(Sort.of("Int"), param.sort());

        // Result var: r_0 : Int.
        assertEquals("r_0", root.resultVar().name());
        assertEquals(Sort.of("Int"), root.resultVar().sort());

        // One unconditional branch, one initial receipt, no sub-calls.
        assertEquals(1, root.branches().size());
        Branch branch = root.branches().get(0);
        assertTrue(branch.guard().isEmpty(), "Unconditional body should have no guard");
        assertEquals(1, branch.initialReceipts().size());
        assertTrue(branch.calls().isEmpty(), "No sub-calls in first slice");

        // The initial receipt: r_0 == n_0 + n_0
        SymExpr expectedClaim = SymExpr.cmp(
                SymExpr.var("r_0"),
                SymExpr.CmpOp.EQ,
                SymExpr.add(SymExpr.var("n_0"), SymExpr.var("n_0")));
        assertEquals(expectedClaim, branch.initialReceipts().get(0).claim());
    }

    /**
     * Empty modules (no function declarations) produce empty receipt-graphs
     * — confirms the drafter doesn't choke on a module containing only the
     * main expression.
     */
    @Test
    void empty_module_yields_empty_graph() throws Exception {
        IrModule module = new IrModule("empty", List.of(), IrExpr.lit(42));

        ReceiptGraph graph = Drafter.draft(module);

        assertTrue(graph.roots().isEmpty());
    }

    /**
     * Multiple function declarations each produce their own root in the
     * graph, keyed by function name.
     */
    @Test
    void drafts_multiple_functions_into_separate_roots() throws Exception {
        // function double(n:Int):Int -> n + n
        // function triple(n:Int):Int -> n + n + n
        IrModule module = new IrModule(
                "test",
                List.of(
                        IrStmt.functionDecl(
                                "double",
                                List.of(new IrParam("n", IrSort.named("Int"))),
                                IrSort.named("Int"),
                                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("n"), IrExpr.var("n"))),
                        IrStmt.functionDecl(
                                "triple",
                                List.of(new IrParam("n", IrSort.named("Int"))),
                                IrSort.named("Int"),
                                IrExpr.binOp(
                                        IrExpr.Op.ADD,
                                        IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("n"), IrExpr.var("n")),
                                        IrExpr.var("n")))),
                IrExpr.lit(0));

        ReceiptGraph graph = Drafter.draft(module);

        assertEquals(2, graph.roots().size());
        assertNotNull(graph.roots().get("double"));
        assertNotNull(graph.roots().get("triple"));

        // Each function has its own call-instance-0 variables — both use n_0
        // and r_0 *independently*. There's no cross-function name collision
        // because each root is keyed by function name.
        Node doubleRoot = graph.roots().get("double");
        Node tripleRoot = graph.roots().get("triple");
        assertEquals("n_0", doubleRoot.params().get(0).name());
        assertEquals("n_0", tripleRoot.params().get(0).name());
        assertEquals("r_0", doubleRoot.resultVar().name());
        assertEquals("r_0", tripleRoot.resultVar().name());
    }
}
