package sibarum.pontif.parser;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrStmt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for operator parsing in {@link PontifParser} under the unified-dispatch
 * model.
 *
 * <p>Operators are symmetric mechanism-1 multi-dispatch free functions. The
 * parser performs NO operator routing: every {@code a <op> b} parses to an
 * {@link IrExpr.BinOp}, and the post-link {@code MethodOperatorResolver}
 * (pontif-ir) routes user-declared operators to their overload by matching
 * BOTH operand base sorts. The receiver-rooted {@code method Type.<op>} form is
 * rejected at parse — operators must be declared as free functions.
 */
class PontifParserOperatorOverloadTest {

    private static IrModule parse(String src) throws ParseException {
        return PontifParser.parseModule(src, "t");
    }

    /** Helper: extracts the body of the last function declaration in a module. */
    private static IrExpr lastFnBody(IrModule m) {
        IrStmt last = m.statements().get(m.statements().size() - 1);
        return ((IrStmt.FunctionDecl) last).body();
    }

    // --- Method-form operators are rejected ---------------------------------

    @Test
    void methodFormOperator_rejected() {
        ParseException ex = assertThrows(ParseException.class, () -> parse("""
                struct Point(x:Int, y:Int)
                method Point.+(p:Point):Point -> Point(this.x + p.x, this.y + p.y)
                """));
        assertTrue(ex.getMessage().contains("operators are free functions"),
                () -> "Unexpected: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("function +(a:T, b:T):R"),
                () -> "Should point to the free-function form: " + ex.getMessage());
    }

    @Test
    void methodFormComparisonOperator_rejected() {
        ParseException ex = assertThrows(ParseException.class, () -> parse("""
                struct Point(x:Int, y:Int)
                method Point.==(p:Point):Bool -> this.x == p.x & this.y == p.y
                """));
        assertTrue(ex.getMessage().contains("operators are free functions"),
                () -> "Unexpected: " + ex.getMessage());
    }

    @Test
    void nonOperatorMethod_stillAccepted() throws Exception {
        // Regular (non-operator) methods are unaffected by the operator rule.
        IrModule m = parse("""
                struct Point(x:Int, y:Int)
                method Point.sum():Int -> this.x + this.y
                """);
        IrStmt.FunctionDecl method = (IrStmt.FunctionDecl) m.statements().get(1);
        assertEquals("Point.sum", method.name());
        assertEquals("this", method.params().get(0).name());
    }

    // --- Bare operator names ------------------------------------------------

    @Test
    void bareOperatorFunction_parsesUnderBareName() throws Exception {
        IrModule m = parse("""
                struct Point(x:Int, y:Int)
                function +(a:Point, b:Point):Point -> Point(a.x + b.x, a.y + b.y)
                """);
        IrStmt.FunctionDecl fn = (IrStmt.FunctionDecl) m.statements().get(1);
        assertEquals("+", fn.name());
        assertEquals(2, fn.params().size());
    }

    @Test
    void qualifiedOperatorFunction_parsesAsDottedName() throws Exception {
        // `function Type.+` (a qualified free function, two explicit operands) is
        // still allowed — only the receiver-rooted `method Type.+` form is barred.
        IrModule m = parse("""
                struct Point(x:Int, y:Int)
                function Point.+(a:Point, b:Point):Point ->
                  Point(a.x + b.x, a.y + b.y)
                """);
        IrStmt.FunctionDecl fn = (IrStmt.FunctionDecl) m.statements().get(1);
        assertEquals("Point.+", fn.name());
    }

    @Test
    void nonOverloadableBareOperator_rejected() {
        // `&` is a valid operator token but excluded from OVERLOADABLE_OPS.
        ParseException ex = assertThrows(ParseException.class, () -> parse("""
                struct Point(x:Int, y:Int)
                function &(a:Point, b:Point):Point -> a
                """));
        assertTrue(ex.getMessage().contains("overloadable"),
                () -> "Unexpected: " + ex.getMessage());
    }

    @Test
    void bareOperatorWrongArity_rejected() {
        ParseException ex = assertThrows(ParseException.class, () -> parse("""
                struct Point(x:Int, y:Int)
                function +(a:Point):Point -> a
                """));
        assertTrue(ex.getMessage().contains("2 parameters"),
                () -> "Unexpected: " + ex.getMessage());
    }

    // --- SexprParser never routes: every operator use stays a BinOp --------------

    @Test
    void structPlusStruct_staysBinOp() throws Exception {
        // No parse-time routing: `a + b` over a struct type stays a BinOp.
        // MethodOperatorResolver routes it to the overload post-link.
        IrModule m = parse("""
                struct Point(x:Int, y:Int)
                function +(a:Point, b:Point):Point -> Point(a.x + b.x, a.y + b.y)
                function add(a:Point, b:Point):Point -> a + b
                """);
        IrExpr.BinOp body = assertInstanceOf(IrExpr.BinOp.class, lastFnBody(m));
        assertEquals(IrExpr.Op.ADD, body.op());
    }

    @Test
    void chainedOps_staysNestedBinOp() throws Exception {
        // a + b + c → BinOp(ADD, BinOp(ADD, a, b), c); both stay BinOp.
        IrModule m = parse("""
                struct Point(x:Int, y:Int)
                function +(a:Point, b:Point):Point -> Point(a.x + b.x, a.y + b.y)
                function tri(a:Point, b:Point, c:Point):Point -> a + b + c
                """);
        IrExpr.BinOp outer = assertInstanceOf(IrExpr.BinOp.class, lastFnBody(m));
        assertEquals(IrExpr.Op.ADD, outer.op());
        assertInstanceOf(IrExpr.BinOp.class, outer.left());
    }

    @Test
    void intPlusInt_staysBinOp() throws Exception {
        IrModule m = parse("""
                struct Point(x:Int, y:Int)
                function +(a:Point, b:Point):Point -> Point(a.x + b.x, a.y + b.y)
                function add(a:Int, b:Int):Int -> a + b
                """);
        IrExpr.BinOp body = assertInstanceOf(IrExpr.BinOp.class, lastFnBody(m));
        assertEquals(IrExpr.Op.ADD, body.op());
    }

    @Test
    void logicalOps_stayBinOp() throws Exception {
        // `&` and `|` always go through BinOp.
        IrModule m = parse("""
                struct Foo(a:Bool)
                function f(x:Bool, y:Bool):Bool -> x & y
                """);
        assertInstanceOf(IrExpr.BinOp.class, lastFnBody(m));
    }

    // --- Refinement-predicate context unaffected ----------------------------

    @Test
    void refinementPredicate_operatorsStayBinOp() throws Exception {
        IrModule m = parse("""
                struct Point(x:Int, y:Int)
                function +(a:Point, b:Point):Point -> Point(a.x + b.x, a.y + b.y)
                function f(n:[Int:@>0 & @<10]):Int -> n
                """);
        IrStmt.FunctionDecl fd = (IrStmt.FunctionDecl) m.statements().get(2);
        sibarum.pontif.ir.IrSort.Refined r =
                (sibarum.pontif.ir.IrSort.Refined) fd.params().get(0).sort();
        assertInstanceOf(IrExpr.BinOp.class, r.predicate());
    }
}
