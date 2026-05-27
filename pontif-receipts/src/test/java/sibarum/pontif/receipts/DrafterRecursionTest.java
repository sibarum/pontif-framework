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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R3 drafter slice: recursion + cross-function calls become
 * {@link CallRef}s with fresh result vars whose sort is the callee's
 * return narrowing. Headline target: the {@code factorial} worked
 * example from {@code docs/receipt-graph.md}.
 */
class DrafterRecursionTest {

    private static IrSort intGe(long n) {
        return IrSort.refined("Int", IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(n)));
    }

    private static IrSort armPattern(IrExpr.Op op, long lit) {
        return IrSort.refined("Int", IrExpr.binOp(op, IrExpr.self(), IrExpr.lit(lit)));
    }

    /**
     * factorial(n:[Int:@>=0]):[Int:@>=1] -> match n
     *   [@==0] -> 1
     *   [@>0 ] -> n * factorial(n - 1)
     */
    private static IrModule factorialModule() {
        IrExpr recursiveBody = IrExpr.binOp(
                IrExpr.Op.MUL,
                IrExpr.var("n"),
                IrExpr.call("factorial",
                        List.of(IrExpr.binOp(IrExpr.Op.SUB, IrExpr.var("n"), IrExpr.lit(1)))));

        return new IrModule("m",
                List.of(IrStmt.functionDecl(
                        "factorial",
                        List.of(new IrParam("n", intGe(0))),
                        intGe(1),
                        IrExpr.match(
                                IrExpr.var("n"),
                                List.of(
                                        IrExpr.matchBranch(armPattern(IrExpr.Op.EQ, 0), IrExpr.lit(1)),
                                        IrExpr.matchBranch(armPattern(IrExpr.Op.GT, 0), recursiveBody))))),
                IrExpr.lit(0));
    }

    @Test
    void factorial_producesWorkedExampleGraph() throws Exception {
        ReceiptGraph graph = Drafter.draft(factorialModule());
        Node root = graph.nodesNamed("factorial").get(0);

        assertEquals(2, root.branches().size());

        // Base arm [n_0 == 0]: no calls, receipt r_0 == 1.
        Branch base = root.branches().get(0);
        assertTrue(base.calls().isEmpty(), "base arm has no calls");
        assertEquals(
                SymExpr.cmp(SymExpr.var("r_0"), SymExpr.CmpOp.EQ, SymExpr.lit(1)),
                base.initialReceipts().get(0).claim());

        // Recursive arm [n_0 > 0]: one back-reference + receipt r_0 == n_0 * r_1.
        Branch recursive = root.branches().get(1);
        assertEquals(1, recursive.calls().size(), "recursive arm has one CallRef");

        CallRef call = recursive.calls().get(0);
        assertEquals("factorial", call.targetFunctionName(),
                "CallRef targets factorial — the back-reference (no-duplicate-edges)");
        assertEquals("r_1", call.resultVar().name());

        // The CallRef's result var carries factorial's return refinement — the
        // inductive hypothesis r_1 >= 1.
        Sort r1Sort = call.resultVar().sort();
        assertTrue(r1Sort.isRefined(), () -> "r_1 should carry the IH; got " + r1Sort);
        assertEquals("Int", r1Sort.name());
        assertEquals(
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(1)),
                r1Sort.predicate(),
                "r_1's refinement is @ >= 1 (factorial's declared return)");

        // The arg binding is n_0 - 1.
        assertEquals(1, call.argBindings().size());
        // n_0 - 1 is encoded Add(n_0, Mul(-1, 1)).
        assertEquals(
                SymExpr.add(SymExpr.var("n_0"), SymExpr.mul(SymExpr.lit(-1), SymExpr.lit(1))),
                call.argBindings().get(0));

        // The body equation references the call result var: r_0 == n_0 * r_1.
        assertEquals(
                SymExpr.cmp(SymExpr.var("r_0"), SymExpr.CmpOp.EQ,
                        SymExpr.mul(SymExpr.var("n_0"), SymExpr.var("r_1"))),
                recursive.initialReceipts().get(0).claim());
    }

    @Test
    void factorial_rendersLikeTheDesignDoc() throws Exception {
        String text = ReceiptGraphPrinter.print(Drafter.draft(factorialModule()));
        System.out.println(text);

        assertTrue(text.contains("factorial(n_0: [Int: @ >= 0]) : r_0: [Int: @ >= 1]"), () -> text);
        assertTrue(text.contains("branch [n_0 == 0]:"), () -> text);
        assertTrue(text.contains("receipt: r_0 == 1"), () -> text);
        assertTrue(text.contains("branch [n_0 > 0]:"), () -> text);
        assertTrue(text.contains("call: factorial(n_0 - 1) -> r_1: [Int: @ >= 1]"), () -> text);
        assertTrue(text.contains("receipt: r_0 == n_0 * r_1"), () -> text);
    }

    @Test
    void factorial_skeletonMatchRoundTrips() throws Exception {
        IrModule module = factorialModule();
        ReceiptGraph graph = Drafter.draft(module);
        assertTrue(Notary.skeletonMatches(graph, module),
                "deterministic re-draft of factorial must equal the original");
    }

    @Test
    void crossFunctionCall_resultVarCarriesCalleeReturn() throws Exception {
        // inc(n:Int):[Int:@>=1] -> n + 1
        // use(n:[Int:@>=0]):[Int:@>=1] -> inc(n)
        // The CallRef in `use` should carry inc's return refinement on r_1.
        IrModule module = new IrModule("m",
                List.of(
                        IrStmt.functionDecl(
                                "inc",
                                List.of(new IrParam("n", IrSort.named("Int"))),
                                intGe(1),
                                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("n"), IrExpr.lit(1))),
                        IrStmt.functionDecl(
                                "use",
                                List.of(new IrParam("n", intGe(0))),
                                intGe(1),
                                IrExpr.call("inc", List.of(IrExpr.var("n"))))),
                IrExpr.lit(0));

        ReceiptGraph graph = Drafter.draft(module);
        Node use = graph.nodesNamed("use").get(0);
        Branch only = use.branches().get(0);

        assertEquals(1, only.calls().size());
        CallRef call = only.calls().get(0);
        assertEquals("inc", call.targetFunctionName());
        assertTrue(call.resultVar().sort().isRefined());
        assertEquals(
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(1)),
                call.resultVar().sort().predicate(),
                "cross-function call result var carries inc's @>=1 return");
        // Body equation: r_0 == r_1 (the call result is the whole body).
        assertEquals(
                SymExpr.cmp(SymExpr.var("r_0"), SymExpr.CmpOp.EQ, SymExpr.var("r_1")),
                only.initialReceipts().get(0).claim());
    }

    @Test
    void nestedCalls_numberedPostOrder() throws Exception {
        // h(n:Int):[Int:@>=1] -> inc(inc(n)) — inner inc = r_1, outer = r_2.
        IrModule module = new IrModule("m",
                List.of(
                        IrStmt.functionDecl(
                                "inc",
                                List.of(new IrParam("n", IrSort.named("Int"))),
                                intGe(1),
                                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("n"), IrExpr.lit(1))),
                        IrStmt.functionDecl(
                                "h",
                                List.of(new IrParam("n", IrSort.named("Int"))),
                                intGe(1),
                                IrExpr.call("inc",
                                        List.of(IrExpr.call("inc", List.of(IrExpr.var("n"))))))),
                IrExpr.lit(0));

        Node h = Drafter.draft(module).nodesNamed("h").get(0);
        Branch only = h.branches().get(0);

        assertEquals(2, only.calls().size(), "two CallRefs: inner then outer");
        assertEquals("r_1", only.calls().get(0).resultVar().name());
        assertEquals("r_2", only.calls().get(1).resultVar().name());
        // Inner call's arg is n_0; outer call's arg is r_1.
        assertEquals(SymExpr.var("n_0"), only.calls().get(0).argBindings().get(0));
        assertEquals(SymExpr.var("r_1"), only.calls().get(1).argBindings().get(0));
        // Body equation: r_0 == r_2.
        assertEquals(
                SymExpr.cmp(SymExpr.var("r_0"), SymExpr.CmpOp.EQ, SymExpr.var("r_2")),
                only.initialReceipts().get(0).claim());
    }
}
