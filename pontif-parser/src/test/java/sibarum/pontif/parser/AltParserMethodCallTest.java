package sibarum.pontif.parser;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrStmt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests for instance-method call routing and dotted-let auto-Call in
 * {@link AltParser}.
 *
 * <p>Instance methods: {@code receiver.method(args)} routes to
 * {@code Call("Type.method", [receiver, args...])} when the receiver's
 * inferred sort has a base name matching a declared method.
 *
 * <p>Dotted-let auto-Call: bare references to a let name (single-ident or
 * dotted) are rewritten to a 0-arg dispatch call. Field access on a let
 * value works ({@code Point.origin.x} → {@code Call("Point.origin", []).x}).
 */
class AltParserMethodCallTest {

    private static IrModule parse(String src) throws ParseException {
        return AltParser.parseModule(src, "t");
    }

    // --- Instance method calls ----------------------------------------------

    @Test
    void methodCall_onParam_routesToTypeMethodWithSelfPrepended() throws Exception {
        String src = """
                struct Point(x:Int, y:Int)
                method Point.shifted(dx:Int, dy:Int):Point ->
                  Point(self.x + dx, self.y + dy)
                function f(p:Point):Point -> p.shifted(1, 2)
                """;
        IrModule m = parse(src);
        IrStmt.FunctionDecl f = (IrStmt.FunctionDecl) m.statements().get(2);
        IrExpr.Call call = assertInstanceOf(IrExpr.Call.class, f.body());
        // Dispatch name is the type-qualified form.
        assertEquals("Point.shifted", call.functionName());
        // Args: receiver, then user-written args.
        assertEquals(3, call.args().size());
        IrExpr.Var receiver = assertInstanceOf(IrExpr.Var.class, call.args().get(0));
        assertEquals("p", receiver.name());
        assertEquals(1L, ((IrExpr.Lit) call.args().get(1)).value());
        assertEquals(2L, ((IrExpr.Lit) call.args().get(2)).value());
    }

    @Test
    void methodCall_onLet_letBecomesZeroArgCallReceiver() throws Exception {
        // The receiver `origin` is a top-level let. It must be rewritten to
        // Call("origin", []) before being passed as `self`.
        String src = """
                struct Point(x:Int, y:Int)
                method Point.magnitudeSq():Int -> self.x * self.x + self.y * self.y
                let origin = Point(3, 4)
                origin.magnitudeSq()
                """;
        IrModule m = parse(src);
        IrExpr.Call call = assertInstanceOf(IrExpr.Call.class, m.main());
        assertEquals("Point.magnitudeSq", call.functionName());
        assertEquals(1, call.args().size());
        // Receiver is Call("origin", []), not Var("origin").
        IrExpr.Call receiver = assertInstanceOf(IrExpr.Call.class, call.args().get(0));
        assertEquals("origin", receiver.functionName());
        assertEquals(0, receiver.args().size());
    }

    @Test
    void methodCall_onCallResult_works() throws Exception {
        // Receiver is itself a Call (a function returning a Point).
        String src = """
                struct Point(x:Int, y:Int)
                method Point.magnitudeSq():Int -> self.x * self.x + self.y * self.y
                function origin():Point -> Point(0, 0)
                origin().magnitudeSq()
                """;
        IrModule m = parse(src);
        IrExpr.Call call = assertInstanceOf(IrExpr.Call.class, m.main());
        assertEquals("Point.magnitudeSq", call.functionName());
        // Receiver is the nested Call to origin().
        IrExpr.Call receiver = assertInstanceOf(IrExpr.Call.class, call.args().get(0));
        assertEquals("origin", receiver.functionName());
    }

    @Test
    void methodCall_fallsBackToFieldDottedCall_whenNoMethodMatches() throws Exception {
        // `Point.staticFn(...)` — `Point` here has no inferred struct sort
        // (it's a type name, not a value), so method routing skips and the
        // existing extractDottedName path produces Call("Point.staticFn", args).
        String src = """
                struct Point(x:Int, y:Int)
                function Point.makeOrigin():Point -> Point(0, 0)
                Point.makeOrigin()
                """;
        IrModule m = parse(src);
        IrExpr.Call call = assertInstanceOf(IrExpr.Call.class, m.main());
        assertEquals("Point.makeOrigin", call.functionName());
        // Args list is empty — NOT prefixed with a synthetic receiver.
        assertEquals(0, call.args().size());
    }

    @Test
    void methodCall_unknownMethod_fallsBackToDottedCall() throws Exception {
        // p has type Point but no method `nonexistent` is registered. The
        // method-routing branch returns null; the existing path then builds
        // Call("p.nonexistent", []) via extractDottedName. At runtime this
        // fails (no such function), which is the correct user-level error.
        String src = """
                struct Point(x:Int, y:Int)
                function f(p:Point):Int -> p.nonexistent()
                """;
        IrModule m = parse(src);
        IrStmt.FunctionDecl f = (IrStmt.FunctionDecl) m.statements().get(1);
        IrExpr.Call call = assertInstanceOf(IrExpr.Call.class, f.body());
        // No method match → falls back to the raw dotted-name Call.
        assertEquals("p.nonexistent", call.functionName());
    }

    // --- Dotted-let auto-Call -----------------------------------------------

    @Test
    void unqualifiedLet_bareReference_rewritesToCall() throws Exception {
        IrModule m = parse("""
                let n = 5
                n + 1
                """);
        IrExpr.BinOp body = assertInstanceOf(IrExpr.BinOp.class, m.main());
        IrExpr.Call left = assertInstanceOf(IrExpr.Call.class, body.left());
        assertEquals("n", left.functionName());
        assertEquals(0, left.args().size());
    }

    @Test
    void dottedLet_bareReference_rewritesToCall() throws Exception {
        IrModule m = parse("""
                struct Point(x:Int, y:Int)
                let Point.origin = Point(0, 0)
                Point.origin
                """);
        IrExpr.Call main = assertInstanceOf(IrExpr.Call.class, m.main());
        assertEquals("Point.origin", main.functionName());
        assertEquals(0, main.args().size());
    }

    @Test
    void dottedLet_fieldAccess_rewritesPrefixOnly() throws Exception {
        // Point.origin is a let; .x is a real field access on its value.
        IrModule m = parse("""
                struct Point(x:Int, y:Int)
                let Point.origin = Point(0, 0)
                Point.origin.x
                """);
        IrExpr.FieldAccess fa = assertInstanceOf(IrExpr.FieldAccess.class, m.main());
        assertEquals("x", fa.fieldName());
        IrExpr.Call base = assertInstanceOf(IrExpr.Call.class, fa.base());
        assertEquals("Point.origin", base.functionName());
    }

    @Test
    void dottedLet_longestPrefixWins() throws Exception {
        // Both `a.b` and `a.b.c` declared — accessing `a.b.c` rewrites the
        // full longest prefix, not the shorter one.
        IrModule m = parse("""
                let a.b = 1
                let a.b.c = 2
                a.b.c
                """);
        IrExpr.Call main = assertInstanceOf(IrExpr.Call.class, m.main());
        assertEquals("a.b.c", main.functionName());
    }

    @Test
    void let_shadowedByFunctionParam_doesNotRewrite() throws Exception {
        // Param `n` shadows the top-level let `n = 99`. Inside the function
        // body, `n` stays as Var (param lookup), no Call rewrite.
        IrModule m = parse("""
                let n = 99
                function f(n:Int):Int -> n + 1
                """);
        IrStmt.FunctionDecl f = (IrStmt.FunctionDecl) m.statements().get(1);
        IrExpr.BinOp body = (IrExpr.BinOp) f.body();
        assertInstanceOf(IrExpr.Var.class, body.left());
    }

    @Test
    void let_shadowedByInExpressionLet_doesNotRewrite() throws Exception {
        IrModule m = parse("""
                let n = 99
                function f():Int -> let n = 5 n + 1
                """);
        IrStmt.FunctionDecl f = (IrStmt.FunctionDecl) m.statements().get(1);
        IrExpr.LetIn outer = (IrExpr.LetIn) f.body();
        IrExpr.BinOp body = (IrExpr.BinOp) outer.body();
        // body's `n` is the in-expression-let's binding, NOT the top-level let.
        assertInstanceOf(IrExpr.Var.class, body.left());
    }

    @Test
    void explicitCall_onLetName_isNotDoubleRewritten() throws Exception {
        // `n()` — explicit call form. We must NOT have a Call wrapping a
        // Call (no double rewrite).
        IrModule m = parse("""
                let n = 5
                n()
                """);
        IrExpr.Call main = assertInstanceOf(IrExpr.Call.class, m.main());
        assertEquals("n", main.functionName());
        assertEquals(0, main.args().size());
        // Verify the receiver isn't itself a Call — it's a flat 0-arg Call.
        // (Sanity: no nested Call structure.)
        assertEquals(IrExpr.Call.class, main.getClass());
    }
}
