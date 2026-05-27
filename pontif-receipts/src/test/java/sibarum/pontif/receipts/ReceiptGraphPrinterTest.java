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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiptGraphPrinterTest {

    @Test
    void rendersSquareGraphFromDrafter() throws Exception {
        // square(x:[Int:@>=0]):[Int:@>=0] -> x * x
        IrSort nonNeg = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(0)));
        IrModule module = new IrModule("m",
                List.of(IrStmt.functionDecl(
                        "square",
                        List.of(new IrParam("x", nonNeg)),
                        nonNeg,
                        IrExpr.binOp(IrExpr.Op.MUL, IrExpr.var("x"), IrExpr.var("x")))),
                IrExpr.lit(0));

        ReceiptGraph graph = Drafter.draft(module);
        String text = ReceiptGraphPrinter.print(graph);

        // Header line: name, param with refined sort, result var with refined sort.
        assertTrue(text.contains("square(x_0: [Int: @ >= 0]) : r_0: [Int: @ >= 0]"),
                () -> "Header line wrong:\n" + text);
        assertTrue(text.contains("branch (unconditional):"),
                () -> "Expected unconditional branch:\n" + text);
        assertTrue(text.contains("receipt: r_0 == x_0 * x_0"),
                () -> "Expected body equation:\n" + text);
    }

    @Test
    void rendersBranchesAndCalls_factorialShape() {
        // Hand-built factorial-shaped graph (the drafter can't produce this
        // until R3) — exercises the printer's branch + call + guard paths.
        Sort nonNeg = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(0)));
        Sort atLeastOne = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(1)));

        // arm [n_0 == 0]: r_0 == 1
        Branch base = new Branch(
                Optional.of(SymExpr.cmp(SymExpr.var("n_0"), SymExpr.CmpOp.EQ, SymExpr.lit(0))),
                List.of(new InitialReceipt(
                        SymExpr.cmp(SymExpr.var("r_0"), SymExpr.CmpOp.EQ, SymExpr.lit(1)))),
                List.of());

        // arm [n_0 > 0]: call factorial(n_0 - 1) -> r_1; r_0 == n_0 * r_1
        SymExpr nMinus1 = SymExpr.add(SymExpr.var("n_0"),
                SymExpr.mul(SymExpr.lit(-1), SymExpr.lit(1)));
        Branch recursive = new Branch(
                Optional.of(SymExpr.cmp(SymExpr.var("n_0"), SymExpr.CmpOp.GT, SymExpr.lit(0))),
                List.of(new InitialReceipt(SymExpr.cmp(
                        SymExpr.var("r_0"), SymExpr.CmpOp.EQ,
                        SymExpr.mul(SymExpr.var("n_0"), SymExpr.var("r_1"))))),
                List.of(new CallRef("factorial",
                        List.of(nMinus1),
                        new Var("r_1", atLeastOne))));

        Node root = new Node("factorial",
                List.of(new Param("n_0", nonNeg)),
                new Var("r_0", atLeastOne),
                List.of(base, recursive));
        ReceiptGraph graph = new ReceiptGraph(List.of(root));

        String text = ReceiptGraphPrinter.print(graph);
        System.out.println(text);

        assertTrue(text.contains("factorial(n_0: [Int: @ >= 0]) : r_0: [Int: @ >= 1]"),
                () -> "Header wrong:\n" + text);
        assertTrue(text.contains("branch [n_0 == 0]:"), () -> "Base-arm guard wrong:\n" + text);
        assertTrue(text.contains("receipt: r_0 == 1"), () -> "Base receipt wrong:\n" + text);
        assertTrue(text.contains("branch [n_0 > 0]:"), () -> "Recursive-arm guard wrong:\n" + text);
        assertTrue(text.contains("call: factorial(n_0 - 1) -> r_1: [Int: @ >= 1]"),
                () -> "Call line wrong (note the Sub rendering):\n" + text);
        assertTrue(text.contains("receipt: r_0 == n_0 * r_1"),
                () -> "Recursive receipt wrong:\n" + text);
    }

    @Test
    void parenthesizesByPrecedence() {
        // (a + b) * c  must keep the parens; a * b + c must not gain any.
        SymExpr withParens = SymExpr.mul(
                SymExpr.add(SymExpr.var("a"), SymExpr.var("b")), SymExpr.var("c"));
        assertEquals("(a + b) * c", ReceiptGraphPrinter.renderSym(withParens));

        SymExpr noParens = SymExpr.add(
                SymExpr.mul(SymExpr.var("a"), SymExpr.var("b")), SymExpr.var("c"));
        assertEquals("a * b + c", ReceiptGraphPrinter.renderSym(noParens));
    }
}
