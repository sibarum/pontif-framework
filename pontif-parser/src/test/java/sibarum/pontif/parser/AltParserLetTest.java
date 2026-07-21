package sibarum.pontif.parser;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for top-level {@code let} in {@link AltParser}. Three shapes are
 * supported:
 * <ul>
 *   <li>{@code let X = value}        — sort inferred maximally from value.
 *   <li>{@code let X:Sort = value}   — declared sort acts as a base-name
 *       sanity check; the binding's effective sort is the inferred sort.
 *   <li>{@code let X:Sort}           — spec-only (NoOp, pending the proof
 *       engine's value synthesis).
 * </ul>
 *
 * <p>Bound names lower to 0-arg {@link IrStmt.FunctionDecl}. Bare references
 * to a let name in expression position are rewritten to {@code Call(name, [])}
 * by {@link AltParser#parsePrimary} so the dispatch table resolves them.
 */
class AltParserLetTest {

    private static IrStmt firstStmt(String src) throws ParseException {
        IrModule m = AltParser.parseModule(src, "t");
        return m.statements().get(0);
    }

    private static IrStmt.FunctionDecl letAsFunction(String src) throws ParseException {
        return assertInstanceOf(IrStmt.FunctionDecl.class, firstStmt(src));
    }

    // --- Inference: literals and bools --------------------------------------

    @Test
    void let_integerLiteral_infersSingletonRefinement() throws Exception {
        IrStmt.FunctionDecl fd = letAsFunction("let x = 5");
        assertEquals("x", fd.name());
        assertTrue(fd.params().isEmpty());
        IrSort.Refined r = assertInstanceOf(IrSort.Refined.class, fd.returnSort());
        assertEquals("Int", r.name());
        // Predicate is `@ == 5`
        IrExpr.BinOp bop = assertInstanceOf(IrExpr.BinOp.class, r.predicate());
        assertEquals(IrExpr.Op.EQ, bop.op());
        assertInstanceOf(IrExpr.SelfRef.class, bop.left());
        assertEquals(5L, ((IrExpr.Lit) bop.right()).value());
    }

    @Test
    void let_boolLiteral_infersSingletonRefinement() throws Exception {
        IrStmt.FunctionDecl fd = letAsFunction("let b = true");
        IrSort.Refined r = assertInstanceOf(IrSort.Refined.class, fd.returnSort());
        assertEquals("Bool", r.name());
        IrExpr.BinOp bop = assertInstanceOf(IrExpr.BinOp.class, r.predicate());
        assertEquals(IrExpr.Op.EQ, bop.op());
        assertEquals(true, ((IrExpr.Bool) bop.right()).value());
    }

    // --- Inference: struct literals -----------------------------------------

    @Test
    void let_recordLiteral_infersStructuralSortWithFieldSingletons() throws Exception {
        String src = "struct Point(x:Int, y:Int)\nlet origin = Point(0, 0)";
        IrModule m = AltParser.parseModule(src, "t");
        IrStmt.FunctionDecl fd = assertInstanceOf(
                IrStmt.FunctionDecl.class, m.statements().get(1));
        IrSort.Structural s = assertInstanceOf(IrSort.Structural.class, fd.returnSort());
        assertEquals("Point", s.name());
        // Each field's sort is the singleton refinement of its literal value.
        IrSort.Refined xSort = assertInstanceOf(IrSort.Refined.class, s.members().get("x"));
        assertEquals("Int", xSort.name());
        IrSort.Refined ySort = assertInstanceOf(IrSort.Refined.class, s.members().get("y"));
        assertEquals("Int", ySort.name());
    }

    @Test
    void let_nestedRecord_infersNestedStructuralSort() throws Exception {
        String src = """
                struct Inner(a:Int)
                struct Outer(inner:Inner, n:Int)
                let o = Outer(Inner(7), 9)
                """;
        IrModule m = AltParser.parseModule(src, "t");
        IrStmt.FunctionDecl fd = assertInstanceOf(
                IrStmt.FunctionDecl.class, m.statements().get(2));
        IrSort.Structural outer = assertInstanceOf(IrSort.Structural.class, fd.returnSort());
        assertEquals("Outer", outer.name());
        IrSort.Structural inner = assertInstanceOf(
                IrSort.Structural.class, outer.members().get("inner"));
        assertEquals("Inner", inner.name());
    }

    // --- Inference: BinOp via implicit @==EXPR sugar ------------------------

    @Test
    void let_binaryArithmetic_infersIntRefinementWithSelfEqExpr() throws Exception {
        IrStmt.FunctionDecl fd = letAsFunction("let z = 1 + 2");
        IrSort.Refined r = assertInstanceOf(IrSort.Refined.class, fd.returnSort());
        assertEquals("Int", r.name());
        // Predicate: @ == (1 + 2)
        IrExpr.BinOp outer = assertInstanceOf(IrExpr.BinOp.class, r.predicate());
        assertEquals(IrExpr.Op.EQ, outer.op());
        assertInstanceOf(IrExpr.SelfRef.class, outer.left());
        IrExpr.BinOp innerSum = assertInstanceOf(IrExpr.BinOp.class, outer.right());
        assertEquals(IrExpr.Op.ADD, innerSum.op());
    }

    @Test
    void let_comparison_infersBoolRefinement() throws Exception {
        IrStmt.FunctionDecl fd = letAsFunction("let b = 1 < 2");
        IrSort.Refined r = assertInstanceOf(IrSort.Refined.class, fd.returnSort());
        assertEquals("Bool", r.name());
    }

    // --- Explicit annotation as base-name sanity check ----------------------

    @Test
    void let_explicitAnnotation_baseMatches_usesInferredSort() throws Exception {
        // `:Int` annotation is consistent with the value's base; the binding's
        // effective sort is still the inferred singleton refinement.
        IrStmt.FunctionDecl fd = letAsFunction("let x:Int = 5");
        IrSort.Refined r = assertInstanceOf(IrSort.Refined.class, fd.returnSort());
        assertEquals("Int", r.name());
        // The annotation does NOT override the inferred [Int:@==5].
        assertInstanceOf(IrExpr.BinOp.class, r.predicate());
    }

    @Test
    void let_explicitAnnotation_baseMismatch_throws() throws Exception {
        // `let p:Int = Point(0,0)` is a trait-free provable mismatch (struct↔primitive) the parser
        // rejects at parse via Assignability (roadmap §4.5 item 1 — the decider is now the engine,
        // not the retired CoercionResolver; the diagnostic is unchanged).
        String src = "struct Point(x:Int, y:Int)\nlet p:Int = Point(0, 0)";
        ParseException ex = assertThrows(ParseException.class, () ->
                AltParser.parseModule(src, "t"));
        assertTrue(ex.getMessage().toLowerCase().contains("different types"),
                () -> "Unexpected: " + ex.getMessage());
    }

    // --- Spec-only and error cases ------------------------------------------

    @Test
    void let_sortOnly_noValue_noDirective_throws() {
        // Synthesis is explicit: a bare `let f:Sort` with no value and no `;`
        // directive is an error, not an implicit synthesis or a silent NoOp.
        ParseException ex = assertThrows(ParseException.class, () ->
                AltParser.parseModule("let f:Int", "t"));
        assertTrue(ex.getMessage().contains("needs a value")
                        && ex.getMessage().contains("';'"),
                () -> "Unexpected: " + ex.getMessage());
    }

    @Test
    void let_sortOnly_nonPinningSort_withDirective_throws() {
        // `;` given but the sort pins no unique witness (Int:@>0 is a range) —
        // an honest "can't synthesize" error, not a NoOp.
        ParseException ex = assertThrows(ParseException.class, () ->
                AltParser.parseModule("let f:[Int:@>0];", "t"));
        assertTrue(ex.getMessage().contains("does not pin a synthesizable value"),
                () -> "Unexpected: " + ex.getMessage());
    }

    @Test
    void let_noSort_noValue_throws() {
        ParseException ex = assertThrows(ParseException.class, () ->
                AltParser.parseModule("let x", "t"));
        assertTrue(ex.getMessage().contains("needs either a sort annotation"),
                () -> "Unexpected: " + ex.getMessage());
    }

    // --- Var→Call rewrite ---------------------------------------------------

    @Test
    void bareReferenceToLet_rewritesToZeroArgCall() throws Exception {
        // `n` in expression position lowers to Call("n", []).
        String src = "let n = 5\nn + 1";
        IrModule m = AltParser.parseModule(src, "t");
        IrExpr.BinOp body = assertInstanceOf(IrExpr.BinOp.class, m.main());
        IrExpr.Call left = assertInstanceOf(IrExpr.Call.class, body.left());
        assertEquals("n", left.functionName());
        assertTrue(left.args().isEmpty());
    }

    @Test
    void fieldAccessOnLetRecord_rewritesBaseToCall() throws Exception {
        // `origin.x` reads as Call("origin", []).x — the FieldAccess base is
        // the rewritten 0-arg call, NOT a bare Var.
        String src = """
                struct Point(x:Int, y:Int)
                let origin = Point(0, 0)
                origin.x
                """;
        IrModule m = AltParser.parseModule(src, "t");
        IrExpr.FieldAccess fa = assertInstanceOf(IrExpr.FieldAccess.class, m.main());
        IrExpr.Call base = assertInstanceOf(IrExpr.Call.class, fa.base());
        assertEquals("origin", base.functionName());
        assertEquals("x", fa.fieldName());
    }

    @Test
    void functionParam_shadowsLet() throws Exception {
        // Inside `f`, `n` is the param — NOT the top-level let. Body must
        // emit Var("n"), not Call("n", []).
        String src = """
                let n = 99
                function f(n:Int):Int -> n + 1
                """;
        IrModule m = AltParser.parseModule(src, "t");
        IrStmt.FunctionDecl fd = assertInstanceOf(
                IrStmt.FunctionDecl.class, m.statements().get(1));
        IrExpr.BinOp body = assertInstanceOf(IrExpr.BinOp.class, fd.body());
        // Inside f, `n` stays as a Var (the param), not a Call.
        assertInstanceOf(IrExpr.Var.class, body.left());
    }

    @Test
    void explicitCallToLet_doesNotDoubleRewrite() throws Exception {
        // `origin()` — user wrote the call explicitly. We must NOT first
        // rewrite origin to Call("origin", []) and then have the postfix turn
        // it into Apply on a Call. The IDENT step should pass through to Var,
        // letting the postfix handle the call form naturally.
        String src = """
                struct Point(x:Int, y:Int)
                let origin = Point(0, 0)
                origin()
                """;
        IrModule m = AltParser.parseModule(src, "t");
        IrExpr.Call call = assertInstanceOf(IrExpr.Call.class, m.main());
        assertEquals("origin", call.functionName());
        assertTrue(call.args().isEmpty());
    }

    // --- Cross-let references -----------------------------------------------

    @Test
    void laterLet_canReferenceEarlierLet_inferredAsBinOp() throws Exception {
        String src = """
                let n = 5
                let m = n + 1
                """;
        IrModule mod = AltParser.parseModule(src, "t");
        IrStmt.FunctionDecl mDecl = assertInstanceOf(
                IrStmt.FunctionDecl.class, mod.statements().get(1));
        // m's body is Call("n", []) + 1
        IrExpr.BinOp body = assertInstanceOf(IrExpr.BinOp.class, mDecl.body());
        assertInstanceOf(IrExpr.Call.class, body.left());
        // m's sort is [Int:@ == (Call("n", []) + 1)]
        IrSort.Refined mSort = assertInstanceOf(IrSort.Refined.class, mDecl.returnSort());
        assertEquals("Int", mSort.name());
    }

    // --- Lowering shape -----------------------------------------------------

    @Test
    void let_lowerstoZeroArgFunctionDecl() throws Exception {
        IrStmt.FunctionDecl fd = letAsFunction("let x = 42");
        assertEquals(List.of(), fd.params());
        // body is the literal
        assertInstanceOf(IrExpr.Lit.class, fd.body());
        assertEquals(42L, ((IrExpr.Lit) fd.body()).value());
    }
}
