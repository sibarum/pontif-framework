package sibarum.pontif.parser;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for in-expression {@code let} in {@link AltParser}.
 *
 * <p>Surface form: {@code let NAME (:Sort)? = VALUE BODY}. No separator
 * between VALUE and BODY — value is parsed greedily via Pratt, body is the
 * next expression. Lowers to {@link IrExpr.LetIn}; the bound name is pushed
 * into the parser's {@link AltParser#currentScope} only for the duration of
 * body parsing.
 */
class AltParserLetExprTest {

    /** Parses a function whose body is the given expression source. */
    private static IrExpr parseFunctionBody(String bodySrc) throws ParseException {
        String src = "function test(n:Int):Int -> " + bodySrc;
        IrModule m = AltParser.parseModule(src, "t");
        IrStmt.FunctionDecl fd = (IrStmt.FunctionDecl) m.statements().get(0);
        return fd.body();
    }

    // --- Basic shape --------------------------------------------------------

    @Test
    void letExpr_basicShape_producesLetIn() throws Exception {
        IrExpr body = parseFunctionBody("let m = 5 m + 1");
        IrExpr.LetIn let = assertInstanceOf(IrExpr.LetIn.class, body);
        assertEquals("m", let.name());
        // value is the literal 5
        assertEquals(5L, ((IrExpr.Lit) let.value()).value());
        // body is m + 1
        IrExpr.BinOp inner = assertInstanceOf(IrExpr.BinOp.class, let.body());
        assertEquals(IrExpr.Op.ADD, inner.op());
        assertInstanceOf(IrExpr.Var.class, inner.left());
        assertEquals("m", ((IrExpr.Var) inner.left()).name());
    }

    @Test
    void letExpr_bindingSort_isInferredMaximalSort() throws Exception {
        IrExpr body = parseFunctionBody("let m = 5 m");
        IrExpr.LetIn let = (IrExpr.LetIn) body;
        // Binding sort is [Int:@==5], not bare Int.
        IrSort.Refined r = assertInstanceOf(IrSort.Refined.class, let.declaredSort());
        assertEquals("Int", r.name());
        assertInstanceOf(IrExpr.BinOp.class, r.predicate());
    }

    @Test
    void letExpr_valueReferencesParam_correctlyResolved() throws Exception {
        // `n` is the function param — value `n + 1` should reference the
        // param's sort during inference.
        IrExpr body = parseFunctionBody("let m = n + 1 m");
        IrExpr.LetIn let = (IrExpr.LetIn) body;
        // Value is BinOp(ADD, Var("n"), Lit(1)) — n stays as Var (param is
        // in currentScope, so Var→Call rewrite is skipped).
        IrExpr.BinOp val = assertInstanceOf(IrExpr.BinOp.class, let.value());
        assertInstanceOf(IrExpr.Var.class, val.left());
    }

    // --- Nesting ------------------------------------------------------------

    @Test
    void letExpr_nestsTwoBindings() throws Exception {
        IrExpr body = parseFunctionBody("let a = 1 let b = 2 a + b");
        IrExpr.LetIn outer = assertInstanceOf(IrExpr.LetIn.class, body);
        assertEquals("a", outer.name());
        IrExpr.LetIn inner = assertInstanceOf(IrExpr.LetIn.class, outer.body());
        assertEquals("b", inner.name());
        // innermost body is a + b — both Vars, both in scope by then.
        IrExpr.BinOp sum = assertInstanceOf(IrExpr.BinOp.class, inner.body());
        assertEquals("a", ((IrExpr.Var) sum.left()).name());
        assertEquals("b", ((IrExpr.Var) sum.right()).name());
    }

    @Test
    void letExpr_innerCanReferenceOuter() throws Exception {
        IrExpr body = parseFunctionBody("let a = 1 let b = a + 1 b");
        IrExpr.LetIn outer = (IrExpr.LetIn) body;
        IrExpr.LetIn inner = (IrExpr.LetIn) outer.body();
        // b's value is `a + 1` — a must resolve as Var (outer let in scope).
        IrExpr.BinOp val = assertInstanceOf(IrExpr.BinOp.class, inner.value());
        assertEquals("a", ((IrExpr.Var) val.left()).name());
    }

    // --- Scoping ------------------------------------------------------------

    @Test
    void letExpr_scopeExpiresAfterBody() throws Exception {
        // After the function body's outer let parse completes, the binding
        // should be gone. Test this indirectly: parse two function decls,
        // second body references the same name without let — should NOT
        // resolve (Var stays unresolved).
        String src = """
                function f(n:Int):Int -> let m = 5 m
                function g(n:Int):Int -> m + 1
                """;
        IrModule mod = AltParser.parseModule(src, "t");
        IrStmt.FunctionDecl g = (IrStmt.FunctionDecl) mod.statements().get(1);
        IrExpr.BinOp body = (IrExpr.BinOp) g.body();
        // m in g's body is just a Var (not in any scope — let scope from f
        // expired). At runtime it'd fail; the parser allows it.
        assertInstanceOf(IrExpr.Var.class, body.left());
        assertEquals("m", ((IrExpr.Var) body.left()).name());
    }

    @Test
    void letExpr_shadowsFunctionParam_inBody() throws Exception {
        // Inside the let body, `n` should refer to the let-bound value (10),
        // not the function param (input value).
        IrExpr body = parseFunctionBody("let n = 10 n + 1");
        IrExpr.LetIn let = (IrExpr.LetIn) body;
        assertEquals("n", let.name());
        // The Var("n") in the body refers to the shadowed binding — but
        // both shadowed and shadowing are Var("n") in the IR (lexically
        // scoped by LetIn). What matters is that no Var→Call rewrite fired
        // (n was in currentScope, so the rewrite was skipped).
        IrExpr.BinOp sum = (IrExpr.BinOp) let.body();
        assertInstanceOf(IrExpr.Var.class, sum.left());
    }

    @Test
    void letExpr_topLevelLetShadowedByInExpressionLet() throws Exception {
        // Top-level let `n = 99` would normally cause Var→Call rewrite for
        // bare `n` in main. Inside this function, an in-expression let `n`
        // shadows that, so the body's `n` stays as Var (in-expression scope
        // wins over declaredTopLevelLets).
        String src = """
                let n = 99
                function f():Int -> let n = 5 n + 1
                """;
        IrModule m = AltParser.parseModule(src, "t");
        IrStmt.FunctionDecl f = (IrStmt.FunctionDecl) m.statements().get(1);
        IrExpr.LetIn let = (IrExpr.LetIn) f.body();
        IrExpr.BinOp body = (IrExpr.BinOp) let.body();
        // Body's `n` should be Var (in-expression let in scope), NOT
        // Call("n", []) (the top-level let, which is shadowed).
        assertInstanceOf(IrExpr.Var.class, body.left());
    }

    // --- Sort annotation ----------------------------------------------------

    @Test
    void letExpr_explicitSort_baseMatches() throws Exception {
        IrExpr body = parseFunctionBody("let m:Int = 5 m");
        IrExpr.LetIn let = (IrExpr.LetIn) body;
        // Inferred sort wins (singleton refinement, not bare Int).
        IrSort.Refined r = assertInstanceOf(IrSort.Refined.class, let.declaredSort());
        assertEquals("Int", r.name());
    }

    @Test
    void letExpr_explicitSort_baseMismatch_throws() {
        // value is Int, annotation says Bool — a trait-free provable mismatch the parser rejects at
        // parse via Assignability (roadmap §4.5 item 1 — the decider is now the engine, not the
        // retired CoercionResolver; the diagnostic is unchanged).
        ParseException ex = assertThrows(ParseException.class, () ->
                parseFunctionBody("let m:Bool = 5 m"));
        assertTrue(ex.getMessage().toLowerCase().contains("different types"),
                () -> "Unexpected: " + ex.getMessage());
    }

    // --- Containers / context -----------------------------------------------

    @Test
    void letExpr_insideCallArg() throws Exception {
        // The let body terminates at the closing `)` of the enclosing call.
        IrExpr body = parseFunctionBody("test(let m = 1 m + 2)");
        // Outer is Call("test", [LetIn(...)])
        IrExpr.Call outerCall = assertInstanceOf(IrExpr.Call.class, body);
        assertEquals("test", outerCall.functionName());
        assertEquals(1, outerCall.args().size());
        IrExpr.LetIn arg = assertInstanceOf(IrExpr.LetIn.class, outerCall.args().get(0));
        assertEquals("m", arg.name());
    }

    @Test
    void letExpr_insideMatchArm_terminatesAtNextArm() throws Exception {
        // The let body terminates at the next arm's `[`. Without that, the
        // greedy parseExpr would try to swallow `[@>=0] -> 0` as a continuation.
        IrExpr body = parseFunctionBody(
                "match n [@<0] -> let m = -n m + 1 [@>=0] -> 0");
        // Body is a Match (possibly wrapped in a LetIn for scrutinee desugar,
        // but n is a Var so no wrapper).
        IrExpr.Match match = assertInstanceOf(IrExpr.Match.class, body);
        assertEquals(2, match.branches().size());
        // First arm's result is the LetIn.
        IrExpr.LetIn arm0 = assertInstanceOf(
                IrExpr.LetIn.class, match.branches().get(0).result());
        assertEquals("m", arm0.name());
        // Second arm's result is just Lit(0).
        assertEquals(0L, ((IrExpr.Lit) match.branches().get(1).result()).value());
    }

    // --- Error case ---------------------------------------------------------

    // --- Block expressions (parens) -----------------------------------------
    // BRACE-AGGREGATES WAR (docs/brace-aggregates.md): the grouping / block role
    // moved from `{…}` to `(…)`. Braces are now exclusively aggregates; a
    // let-chain bound to its closing delimiter is written `( let … )`.

    @Test
    void blockExpr_unwrapsToInnerExpression() throws Exception {
        // `( EXPR )` is a pure delimiter — it evaluates to its inner expression.
        IrExpr body = parseFunctionBody("( 5 )");
        assertEquals(5L, ((IrExpr.Lit) body).value());
    }

    @Test
    void blockExpr_aroundLetChain_terminatesAtClosingParen() throws Exception {
        // Multi-let body wrapped in `(...)`. The closing `)` terminates the
        // innermost let's body parse cleanly.
        IrExpr body = parseFunctionBody("""
                (
                  let a = 1
                  let b = 2
                  a + b
                )""");
        IrExpr.LetIn outer = assertInstanceOf(IrExpr.LetIn.class, body);
        assertEquals("a", outer.name());
        IrExpr.LetIn inner = assertInstanceOf(IrExpr.LetIn.class, outer.body());
        assertEquals("b", inner.name());
    }

    @Test
    void blockExpr_usableAsLetValue() throws Exception {
        // `let x = ( let y = 5 y + 1 ) x * 2`
        IrExpr body = parseFunctionBody("let x = ( let y = 5 y + 1 ) x * 2");
        IrExpr.LetIn outer = assertInstanceOf(IrExpr.LetIn.class, body);
        assertEquals("x", outer.name());
        // x's value is the inner LetIn (the paren block unwrapped).
        IrExpr.LetIn xValue = assertInstanceOf(IrExpr.LetIn.class, outer.value());
        assertEquals("y", xValue.name());
    }

    @Test
    void blockExpr_usableAsCallArg() throws Exception {
        IrExpr body = parseFunctionBody("test(( let a = 1 a + 1 ))");
        IrExpr.Call call = assertInstanceOf(IrExpr.Call.class, body);
        assertEquals(1, call.args().size());
        assertInstanceOf(IrExpr.LetIn.class, call.args().get(0));
    }

    @Test
    void blockExpr_distinctFromStructLiteral() throws Exception {
        // The paren block `( … )` and the by-name struct literal `Point{x=1, y=2}`
        // (preceded by a Var resolving via declaredStructs) coexist. (Standalone
        // `{…}` is now a positional aggregate, no longer a block.)
        String src = """
                struct Point(x:Int, y:Int)
                function f(n:Int):Int -> (
                  let p = Point{x=1, y=2}
                  p.x + p.y
                )
                """;
        IrModule m = AltParser.parseModule(src, "t");
        IrStmt.FunctionDecl fd = (IrStmt.FunctionDecl) m.statements().get(1);
        IrExpr.LetIn let = assertInstanceOf(IrExpr.LetIn.class, fd.body());
        // The value is a struct literal (Record), NOT a block-unwrapped expr.
        assertInstanceOf(IrExpr.Record.class, let.value());
    }

    // --- Error cases --------------------------------------------------------

    @Test
    void letExpr_keywordAsName_throws() {
        ParseException ex = assertThrows(ParseException.class, () ->
                parseFunctionBody("let match = 5 match"));
        assertTrue(ex.getMessage().toLowerCase().contains("keyword"),
                () -> "Unexpected: " + ex.getMessage());
    }
}
