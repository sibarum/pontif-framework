package sibarum.pontif.demo.parser;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrParam;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserTest {

    private static IrExpr expr(String s) {
        return Parser.parseExpr(s, "t.ptf");
    }

    private static IrSort sort(String s) {
        return Parser.parseSort(s, "t.ptf");
    }

    // --- Literals and atoms ---

    @Test
    void integerLiteral_parsesToLit() {
        IrExpr e = expr("42");
        assertInstanceOf(IrExpr.Lit.class, e);
        assertEquals(42L, ((IrExpr.Lit) e).value());
    }

    @Test
    void negativeIntegerLiteral_parsesToLit() {
        IrExpr e = expr("-7");
        assertInstanceOf(IrExpr.Lit.class, e);
        assertEquals(-7L, ((IrExpr.Lit) e).value());
    }

    @Test
    void trueAndFalse_parseToBool() {
        assertInstanceOf(IrExpr.Bool.class, expr("true"));
        assertEquals(true, ((IrExpr.Bool) expr("true")).value());
        assertEquals(false, ((IrExpr.Bool) expr("false")).value());
    }

    @Test
    void self_parsesToSelfRef() {
        assertInstanceOf(IrExpr.SelfRef.class, expr("self"));
    }

    @Test
    void bareIdentifier_parsesToVar() {
        IrExpr e = expr("counter");
        assertInstanceOf(IrExpr.Var.class, e);
        assertEquals("counter", ((IrExpr.Var) e).name());
    }

    // --- Binary operators ---

    @Test
    void addition_parsesToBinOpAdd() {
        IrExpr e = expr("(+ 1 2)");
        assertInstanceOf(IrExpr.BinOp.class, e);
        IrExpr.BinOp op = (IrExpr.BinOp) e;
        assertEquals(IrExpr.Op.ADD, op.op());
        assertEquals(1L, ((IrExpr.Lit) op.left()).value());
        assertEquals(2L, ((IrExpr.Lit) op.right()).value());
    }

    @Test
    void allComparisonOperators_parseCorrectly() {
        assertEquals(IrExpr.Op.LT, ((IrExpr.BinOp) expr("(< 1 2)")).op());
        assertEquals(IrExpr.Op.LE, ((IrExpr.BinOp) expr("(<= 1 2)")).op());
        assertEquals(IrExpr.Op.GT, ((IrExpr.BinOp) expr("(> 1 2)")).op());
        assertEquals(IrExpr.Op.GE, ((IrExpr.BinOp) expr("(>= 1 2)")).op());
        assertEquals(IrExpr.Op.EQ, ((IrExpr.BinOp) expr("(== 1 2)")).op());
        assertEquals(IrExpr.Op.NE, ((IrExpr.BinOp) expr("(!= 1 2)")).op());
    }

    @Test
    void nestedBinaryOps_parseCorrectly() {
        // (+ 1 (* 2 3))
        IrExpr e = expr("(+ 1 (* 2 3))");
        IrExpr.BinOp add = (IrExpr.BinOp) e;
        IrExpr.BinOp mul = (IrExpr.BinOp) add.right();
        assertEquals(IrExpr.Op.ADD, add.op());
        assertEquals(IrExpr.Op.MUL, mul.op());
    }

    // --- Forms ---

    @Test
    void letForm_parsesWithSortAndBody() {
        IrExpr e = expr("(let x Int 5 (+ x 3))");
        IrExpr.LetIn let = (IrExpr.LetIn) e;
        assertEquals("x", let.name());
        assertInstanceOf(IrSort.Named.class, let.declaredSort());
        assertEquals(5L, ((IrExpr.Lit) let.value()).value());
        assertInstanceOf(IrExpr.BinOp.class, let.body());
    }

    @Test
    void callForm_parsesWithMultipleArguments() {
        IrExpr e = expr("(call factorial 5)");
        IrExpr.Call call = (IrExpr.Call) e;
        assertEquals("factorial", call.functionName());
        assertEquals(1, call.args().size());
        assertEquals(5L, ((IrExpr.Lit) call.args().get(0)).value());
    }

    @Test
    void matchForm_parsesScrutineeAndBranches() {
        IrExpr e = expr("(match x ((refined Int (== self 0)) 1) ((refined Int (> self 0)) 2))");
        IrExpr.Match m = (IrExpr.Match) e;
        assertInstanceOf(IrExpr.Var.class, m.scrutinee());
        assertEquals(2, m.branches().size());
        assertInstanceOf(IrSort.Refined.class, m.branches().get(0).pattern());
        assertEquals(1L, ((IrExpr.Lit) m.branches().get(0).result()).value());
    }

    // --- Sorts ---

    @Test
    void bareIdentifier_parsesAsNamedSort() {
        IrSort s = sort("Int");
        assertInstanceOf(IrSort.Named.class, s);
        assertEquals("Int", ((IrSort.Named) s).name());
    }

    @Test
    void refinedSort_parsesWithPredicate() {
        IrSort s = sort("(refined Int (> self 0))");
        assertInstanceOf(IrSort.Refined.class, s);
        IrSort.Refined r = (IrSort.Refined) s;
        assertEquals("Int", r.name());
        IrExpr.BinOp pred = (IrExpr.BinOp) r.predicate();
        assertEquals(IrExpr.Op.GT, pred.op());
        assertInstanceOf(IrExpr.SelfRef.class, pred.left());
    }

    // --- Module + function decl ---

    @Test
    void module_withSingleFunctionDecl_parses() {
        IrModule m = Parser.parseModule(
                "(module square ((defn square ((n Int)) Int (* n n))) (call square 5))",
                "t.ptf");
        assertEquals("square", m.name());
        assertEquals(1, m.statements().size());
        IrStmt.FunctionDecl decl = (IrStmt.FunctionDecl) m.statements().get(0);
        assertEquals("square", decl.name());
        assertEquals(1, decl.params().size());
        IrParam p = decl.params().get(0);
        assertEquals("n", p.name());
        assertEquals("Int", ((IrSort.Named) p.sort()).name());
    }

    @Test
    void module_withMultipleFunctionDecls_parses() {
        // Demonstrates parsing multiple defns
        IrModule m = Parser.parseModule(
                "(module m ((defn a ((x Int)) Int x) (defn b ((y Int)) Int y)) (call a 1))",
                "t.ptf");
        assertEquals(2, m.statements().size());
    }

    // --- Origin propagation ---

    @Test
    void origins_carryFileNameAndPosition() {
        IrExpr e = expr("(+ 1 2)");
        IrExpr.BinOp op = (IrExpr.BinOp) e;
        assertTrue(op.origin().isPresent(), "origin should be set for parsed BinOp");
        assertTrue(op.origin().toString().contains("t.ptf"),
                "origin should carry source name; got: " + op.origin());
    }

    @Test
    void leftHandLit_carriesItsOwnOrigin() {
        IrExpr e = expr("(+ 1 2)");
        IrExpr.BinOp op = (IrExpr.BinOp) e;
        assertTrue(op.left().origin().isPresent());
        assertTrue(op.right().origin().isPresent());
    }

    // --- Errors ---

    @Test
    void reservedWordAsVariable_throwsWithSuggestion() {
        ParseException ex = assertThrows(ParseException.class, () -> expr("let"));
        assertTrue(ex.getMessage().contains("Reserved"),
                "error should mention reserved word; got: " + ex.getMessage());
    }

    @Test
    void mismatchedParen_throwsWithOrigin() {
        ParseException ex = assertThrows(ParseException.class, () -> expr("(+ 1 2"));
        assertTrue(ex.getMessage().contains("t.ptf"),
                "error should include source name; got: " + ex.getMessage());
    }

    @Test
    void emptyMatch_throwsWithExplanation() {
        ParseException ex = assertThrows(ParseException.class, () -> expr("(match 5)"));
        assertTrue(ex.getMessage().toLowerCase().contains("branch"),
                "error should mention missing branches; got: " + ex.getMessage());
    }

    @Test
    void unknownForm_throws() {
        ParseException ex = assertThrows(ParseException.class, () -> expr("(unknownForm 1 2)"));
        assertTrue(ex.getMessage().contains("unknownForm")
                        || ex.getMessage().toLowerCase().contains("unknown"),
                "error should mention the unknown form; got: " + ex.getMessage());
    }
}
