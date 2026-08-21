package sibarum.pontif.parser;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Tests for bare-access auto-Call of 0-arg dispatch entries (lets and 0-arg
 * functions). The two forms are treated symmetrically — both auto-Call on
 * unqualified or dotted bare reference; functions with at least one declared
 * param require explicit parens.
 */
class PontifParserStaticAccessTest {

    private static IrModule parse(String src) throws ParseException {
        return PontifParser.parseModule(src, "t");
    }

    // --- Symmetric with let: 0-arg function bare access -----------------------

    @Test
    void unqualifiedZeroArgFunction_bareReference_rewritesToCall() throws Exception {
        IrModule m = parse("""
                function five():Int -> 5
                five + 1
                """);
        IrExpr.BinOp body = assertInstanceOf(IrExpr.BinOp.class, m.main());
        IrExpr.Call left = assertInstanceOf(IrExpr.Call.class, body.left());
        assertEquals("five", left.functionName());
        assertEquals(0, left.args().size());
    }

    @Test
    void dottedZeroArgFunction_bareReference_rewritesToCall() throws Exception {
        IrModule m = parse("""
                struct Point(x:Int, y:Int)
                function Point.zero():Point -> Point(0, 0)
                Point.zero
                """);
        IrExpr.Call main = assertInstanceOf(IrExpr.Call.class, m.main());
        assertEquals("Point.zero", main.functionName());
        assertEquals(0, main.args().size());
    }

    @Test
    void dottedZeroArgFunction_followedByFieldAccess() throws Exception {
        IrModule m = parse("""
                struct Point(x:Int, y:Int)
                function Point.zero():Point -> Point(0, 0)
                Point.zero.x
                """);
        IrExpr.FieldAccess fa = assertInstanceOf(IrExpr.FieldAccess.class, m.main());
        assertEquals("x", fa.fieldName());
        IrExpr.Call base = assertInstanceOf(IrExpr.Call.class, fa.base());
        assertEquals("Point.zero", base.functionName());
    }

    // --- Functions with params do NOT auto-call -----------------------------

    @Test
    void multiArgFunction_bareReference_staysAsVar() throws Exception {
        // `foo` is a 1-arg function — bare reference should NOT auto-Call.
        // Stays as Var (at runtime: unresolved symbol, expected behavior).
        IrModule m = parse("""
                function foo(n:Int):Int -> n + 1
                foo
                """);
        assertInstanceOf(IrExpr.Var.class, m.main());
    }

    @Test
    void multiArgFunction_explicitCall_works() throws Exception {
        IrModule m = parse("""
                function foo(n:Int):Int -> n + 1
                foo(5)
                """);
        IrExpr.Call call = assertInstanceOf(IrExpr.Call.class, m.main());
        assertEquals("foo", call.functionName());
        assertEquals(1, call.args().size());
    }

    // --- Symmetry with let ---------------------------------------------------

    @Test
    void letAndZeroArgFunction_areInterchangeableAtUseSite() throws Exception {
        // Both produce the same Call shape at the use site.
        IrModule viaLet = parse("""
                let five = 5
                five + 1
                """);
        IrModule viaFn = parse("""
                function five():Int -> 5
                five + 1
                """);

        IrExpr.BinOp viaLetBody = (IrExpr.BinOp) viaLet.main();
        IrExpr.BinOp viaFnBody = (IrExpr.BinOp) viaFn.main();
        IrExpr.Call letCall = (IrExpr.Call) viaLetBody.left();
        IrExpr.Call fnCall = (IrExpr.Call) viaFnBody.left();
        assertEquals(letCall.functionName(), fnCall.functionName());
        assertEquals(letCall.args(), fnCall.args());
    }

    // --- Scoping -------------------------------------------------------------

    @Test
    void functionParam_shadowsTopLevelZeroArgFunction() throws Exception {
        // `five` is a function param inside f — must NOT route as a 0-arg
        // call to the top-level `five()`.
        IrModule m = parse("""
                function five():Int -> 99
                function f(five:Int):Int -> five + 1
                """);
        sibarum.pontif.ir.IrStmt.FunctionDecl f =
                (sibarum.pontif.ir.IrStmt.FunctionDecl) m.statements().get(1);
        IrExpr.BinOp body = (IrExpr.BinOp) f.body();
        // `five` here is the param (Var), not a Call to the top-level fn.
        assertInstanceOf(IrExpr.Var.class, body.left());
    }

    @Test
    void inExpressionLet_shadowsTopLevelZeroArgFunction() throws Exception {
        // Same shadowing for in-expression let.
        IrModule m = parse("""
                function five():Int -> 99
                function f():Int -> let five = 5 five + 1
                """);
        sibarum.pontif.ir.IrStmt.FunctionDecl f =
                (sibarum.pontif.ir.IrStmt.FunctionDecl) m.statements().get(1);
        IrExpr.LetIn outer = (IrExpr.LetIn) f.body();
        IrExpr.BinOp body = (IrExpr.BinOp) outer.body();
        // body's `five` is the let-bound 5, NOT a Call.
        assertInstanceOf(IrExpr.Var.class, body.left());
    }

    // --- Method vs static distinction ----------------------------------------

    @Test
    void zeroArgMethod_isNotAutoCalled_becauseSelfMakesItOneArg() throws Exception {
        // `method Point.getDefault()` desugars to a 1-arg function
        // (self:Point). So `Point.getDefault` bare reference should NOT
        // route — it's not 0-arg.
        IrModule m = parse("""
                struct Point(x:Int, y:Int)
                method Point.getDefault():Point -> Point(0, 0)
                Point.getDefault
                """);
        // Stays as FieldAccess(Var("Point"), "getDefault"). At runtime
        // unresolved — which is correct, because there's no static
        // Point.getDefault(); only the instance method exists.
        assertInstanceOf(IrExpr.FieldAccess.class, m.main());
    }
}
