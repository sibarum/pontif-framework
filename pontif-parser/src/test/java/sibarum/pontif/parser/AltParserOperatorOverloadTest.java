package sibarum.pontif.parser;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrStmt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Tests for operator-overload routing in {@link AltParser}.
 *
 * <p>When a binary expression's left operand has a struct sort with a
 * declared {@code Type.<op>} method, the parser emits a Call to that
 * method instead of a primitive {@code IrExpr.BinOp}. Primitive operands
 * (Int, Bool) always keep the BinOp path so the existing fast arithmetic
 * isn't disturbed.
 */
class AltParserOperatorOverloadTest {

    private static IrModule parse(String src) throws ParseException {
        return AltParser.parseModule(src, "t");
    }

    /** Helper: extracts the body of the last function declaration in a module. */
    private static IrExpr lastFnBody(IrModule m) {
        IrStmt last = m.statements().get(m.statements().size() - 1);
        return ((IrStmt.FunctionDecl) last).body();
    }

    // --- Method-name parsing ------------------------------------------------

    @Test
    void methodWithOperatorName_parsesAsDottedDispatchKey() throws Exception {
        IrModule m = parse("""
                struct Point(x:Int, y:Int)
                method Point.+(p:Point):Point ->
                  Point(self.x + p.x, self.y + p.y)
                """);
        IrStmt.FunctionDecl method = (IrStmt.FunctionDecl) m.statements().get(1);
        assertEquals("Point.+", method.name());
        // self is the injected receiver param
        assertEquals("self", method.params().get(0).name());
    }

    @Test
    void functionWithOperatorNameInQualifier_parsesAsDottedDispatchKey() throws Exception {
        IrModule m = parse("""
                struct Point(x:Int, y:Int)
                function Point.+(a:Point, b:Point):Point ->
                  Point(a.x + b.x, a.y + b.y)
                """);
        IrStmt.FunctionDecl fn = (IrStmt.FunctionDecl) m.statements().get(1);
        assertEquals("Point.+", fn.name());
    }

    // --- Routing: struct operand → Call -------------------------------------

    @Test
    void structPlusStruct_routesToTypeOpMethod() throws Exception {
        IrModule m = parse("""
                struct Point(x:Int, y:Int)
                method Point.+(p:Point):Point -> Point(self.x + p.x, self.y + p.y)
                function add(a:Point, b:Point):Point -> a + b
                """);
        IrExpr.Call call = assertInstanceOf(IrExpr.Call.class, lastFnBody(m));
        assertEquals("Point.+", call.functionName());
        // Two args: receiver, then the right operand
        assertEquals(2, call.args().size());
        assertEquals("a", ((IrExpr.Var) call.args().get(0)).name());
        assertEquals("b", ((IrExpr.Var) call.args().get(1)).name());
    }

    @Test
    void structComparison_routesToTypeOpMethod() throws Exception {
        IrModule m = parse("""
                struct Point(x:Int, y:Int)
                method Point.==(p:Point):Bool -> self.x == p.x & self.y == p.y
                function eq(a:Point, b:Point):Bool -> a == b
                """);
        IrExpr.Call call = assertInstanceOf(IrExpr.Call.class, lastFnBody(m));
        assertEquals("Point.==", call.functionName());
    }

    @Test
    void chainedOverloadedOps_associateLeftToRight() throws Exception {
        // a + b + c → ((a + b) + c)
        // First + routes to Point.+, producing a Call. Its sort is Point.
        // Second + also routes (Point.+ exists), producing another Call.
        IrModule m = parse("""
                struct Point(x:Int, y:Int)
                method Point.+(p:Point):Point -> Point(self.x + p.x, self.y + p.y)
                function tri(a:Point, b:Point, c:Point):Point -> a + b + c
                """);
        IrExpr.Call outer = assertInstanceOf(IrExpr.Call.class, lastFnBody(m));
        assertEquals("Point.+", outer.functionName());
        // outer's first arg is the inner (a + b) Call
        IrExpr.Call inner = assertInstanceOf(IrExpr.Call.class, outer.args().get(0));
        assertEquals("Point.+", inner.functionName());
    }

    // --- Non-routing cases: primitive path stays BinOp ----------------------

    @Test
    void intPlusInt_staysBinOp_regardlessOfDeclaredOverloads() throws Exception {
        // Point.+ is declared but operands are Int → BinOp path.
        IrModule m = parse("""
                struct Point(x:Int, y:Int)
                method Point.+(p:Point):Point -> Point(self.x + p.x, self.y + p.y)
                function add(a:Int, b:Int):Int -> a + b
                """);
        IrExpr.BinOp body = assertInstanceOf(IrExpr.BinOp.class, lastFnBody(m));
        assertEquals(IrExpr.Op.ADD, body.op());
    }

    @Test
    void mixedTypes_leftIsInt_staysBinOp() throws Exception {
        // a:Int + b:Point → left is Int (primitive), stays BinOp.
        // Will fail at runtime since BinOp doesn't handle Int+Point, but the
        // parser correctly identifies it as the primitive path (left's type wins).
        IrModule m = parse("""
                struct Point(x:Int, y:Int)
                method Point.+(p:Point):Point -> Point(self.x + p.x, self.y + p.y)
                function f(a:Int, b:Point):Int -> a + 1
                """);
        assertInstanceOf(IrExpr.BinOp.class, lastFnBody(m));
    }

    @Test
    void structOp_withoutDeclaredOverload_staysBinOp() throws Exception {
        // Struct sort but no Point.- declared → BinOp falls through. Runtime
        // will reject the non-primitive operand, but the parser doesn't
        // pre-emptively route.
        IrModule m = parse("""
                struct Point(x:Int, y:Int)
                method Point.+(p:Point):Point -> Point(self.x + p.x, self.y + p.y)
                function diff(a:Point, b:Point):Point -> a - b
                """);
        assertInstanceOf(IrExpr.BinOp.class, lastFnBody(m));
    }

    @Test
    void logicalOps_neverRoute_evenWhenDeclared() throws Exception {
        // `&` and `|` are explicitly excluded from overloading in this slice
        // — even if a Type.& method is declared, BinOp wins.
        IrModule m = parse("""
                struct Foo(a:Bool)
                method Foo.&(other:Foo):Foo -> Foo(self.a & other.a)
                function f(x:Foo, y:Foo):Foo -> x & y
                """);
        // Should still be BinOp(AND, x, y) — not routed to Foo.&.
        assertInstanceOf(IrExpr.BinOp.class, lastFnBody(m));
    }

    // --- Refinement-predicate context unaffected ----------------------------

    @Test
    void refinementPredicate_operatorsStayBinOp() throws Exception {
        // `@>0 & @<10` inside a refinement uses @-as-SelfRef, whose inferred
        // sort is "_" (placeholder). Routing skips, so the predicate stays
        // a pure-primitive BinOp tree regardless of any declared overloads.
        IrModule m = parse("""
                struct Point(x:Int, y:Int)
                method Point.+(p:Point):Point -> Point(self.x + p.x, self.y + p.y)
                function f(n:[Int:@>0 & @<10]):Int -> n
                """);
        IrStmt.FunctionDecl fd = (IrStmt.FunctionDecl) m.statements().get(2);
        // The param's sort is Refined; the predicate must be a BinOp(AND, ...).
        sibarum.pontif.ir.IrSort.Refined r =
                (sibarum.pontif.ir.IrSort.Refined) fd.params().get(0).sort();
        assertInstanceOf(IrExpr.BinOp.class, r.predicate());
    }
}
