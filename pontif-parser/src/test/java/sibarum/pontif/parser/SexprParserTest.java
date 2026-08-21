package sibarum.pontif.parser;

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

class SexprParserTest {

    private static IrExpr expr(String s) throws ParseException {
        return SexprParser.parseExpr(s, "t.ptf");
    }

    private static IrSort sort(String s) throws ParseException {
        return SexprParser.parseSort(s, "t.ptf");
    }

    // --- Literals and atoms ---

    @Test
    void integerLiteral_parsesToLit() throws Exception {
        IrExpr e = expr("42");
        assertInstanceOf(IrExpr.Lit.class, e);
        assertEquals(42L, ((IrExpr.Lit) e).value());
    }

    @Test
    void negativeIntegerLiteral_parsesToLit() throws Exception {
        IrExpr e = expr("-7");
        assertInstanceOf(IrExpr.Lit.class, e);
        assertEquals(-7L, ((IrExpr.Lit) e).value());
    }

    @Test
    void trueAndFalse_parseToBool() throws Exception {
        assertInstanceOf(IrExpr.Bool.class, expr("true"));
        assertEquals(true, ((IrExpr.Bool) expr("true")).value());
        assertEquals(false, ((IrExpr.Bool) expr("false")).value());
    }

    @Test
    void self_parsesToSelfRef() throws Exception {
        assertInstanceOf(IrExpr.SelfRef.class, expr("self"));
    }

    @Test
    void bareIdentifier_parsesToVar() throws Exception {
        IrExpr e = expr("counter");
        assertInstanceOf(IrExpr.Var.class, e);
        assertEquals("counter", ((IrExpr.Var) e).name());
    }

    // --- Binary operators ---

    @Test
    void addition_parsesToBinOpAdd() throws Exception {
        IrExpr e = expr("(+ 1 2)");
        assertInstanceOf(IrExpr.BinOp.class, e);
        IrExpr.BinOp op = (IrExpr.BinOp) e;
        assertEquals(IrExpr.Op.ADD, op.op());
        assertEquals(1L, ((IrExpr.Lit) op.left()).value());
        assertEquals(2L, ((IrExpr.Lit) op.right()).value());
    }

    @Test
    void booleanOperators_parseToBinOp() throws Exception {
        assertEquals(IrExpr.Op.AND, ((IrExpr.BinOp) expr("(&& true false)")).op());
        assertEquals(IrExpr.Op.OR, ((IrExpr.BinOp) expr("(|| true false)")).op());
    }

    @Test
    void allComparisonOperators_parseCorrectly() throws Exception {
        assertEquals(IrExpr.Op.LT, ((IrExpr.BinOp) expr("(< 1 2)")).op());
        assertEquals(IrExpr.Op.LE, ((IrExpr.BinOp) expr("(<= 1 2)")).op());
        assertEquals(IrExpr.Op.GT, ((IrExpr.BinOp) expr("(> 1 2)")).op());
        assertEquals(IrExpr.Op.GE, ((IrExpr.BinOp) expr("(>= 1 2)")).op());
        assertEquals(IrExpr.Op.EQ, ((IrExpr.BinOp) expr("(== 1 2)")).op());
        assertEquals(IrExpr.Op.NE, ((IrExpr.BinOp) expr("(!= 1 2)")).op());
    }

    @Test
    void nestedBinaryOps_parseCorrectly() throws Exception {
        // (+ 1 (* 2 3))
        IrExpr e = expr("(+ 1 (* 2 3))");
        IrExpr.BinOp add = (IrExpr.BinOp) e;
        IrExpr.BinOp mul = (IrExpr.BinOp) add.right();
        assertEquals(IrExpr.Op.ADD, add.op());
        assertEquals(IrExpr.Op.MUL, mul.op());
    }

    // --- Forms ---

    @Test
    void letForm_parsesWithSortAndBody() throws Exception {
        IrExpr e = expr("(let x Int 5 (+ x 3))");
        IrExpr.LetIn let = (IrExpr.LetIn) e;
        assertEquals("x", let.name());
        assertInstanceOf(IrSort.Named.class, let.declaredSort());
        assertEquals(5L, ((IrExpr.Lit) let.value()).value());
        assertInstanceOf(IrExpr.BinOp.class, let.body());
    }

    @Test
    void callForm_parsesWithMultipleArguments() throws Exception {
        IrExpr e = expr("(call factorial 5)");
        IrExpr.Call call = (IrExpr.Call) e;
        assertEquals("factorial", call.functionName());
        assertEquals(1, call.args().size());
        assertEquals(5L, ((IrExpr.Lit) call.args().get(0)).value());
    }

    @Test
    void matchForm_parsesScrutineeAndBranches() throws Exception {
        IrExpr e = expr("(match x ((refined Int (== self 0)) 1) ((refined Int (> self 0)) 2))");
        IrExpr.Match m = (IrExpr.Match) e;
        assertInstanceOf(IrExpr.Var.class, m.scrutinee());
        assertEquals(2, m.branches().size());
        assertInstanceOf(IrSort.Refined.class, m.branches().get(0).pattern());
        assertEquals(1L, ((IrExpr.Lit) m.branches().get(0).result()).value());
    }

    @Test
    void lambdaForm_parsesParamsReturnSortAndBody() throws Exception {
        IrExpr e = expr("(lambda ((x Int)) Int (+ x 1))");
        IrExpr.Lambda lam = (IrExpr.Lambda) e;
        assertEquals(1, lam.params().size());
        assertEquals("x", lam.params().get(0).name());
        assertEquals("Int", ((IrSort.Named) lam.params().get(0).sort()).name());
        assertEquals("Int", ((IrSort.Named) lam.returnSort()).name());
        assertInstanceOf(IrExpr.BinOp.class, lam.body());
    }

    @Test
    void lambdaWithMultipleParams_parses() throws Exception {
        IrExpr e = expr("(lambda ((x Int) (y Int)) Int (* x y))");
        IrExpr.Lambda lam = (IrExpr.Lambda) e;
        assertEquals(2, lam.params().size());
        assertEquals("x", lam.params().get(0).name());
        assertEquals("y", lam.params().get(1).name());
    }

    @Test
    void lambdaWithZeroParams_parses() throws Exception {
        IrExpr e = expr("(lambda () Int 42)");
        IrExpr.Lambda lam = (IrExpr.Lambda) e;
        assertEquals(0, lam.params().size());
        assertEquals(42L, ((IrExpr.Lit) lam.body()).value());
    }

    @Test
    void callOnBareSymbol_parsesToCall_evenIfHeadCouldBeAClosure() throws Exception {
        // The parser doesn't try to decide call-vs-apply by the head's semantics;
        // any bare symbol head produces IrExpr.Call. The runtime then resolves
        // it: local binding (closure) first, dispatch table second.
        IrExpr e = expr("(call f 1 2)");
        IrExpr.Call call = (IrExpr.Call) e;
        assertEquals("f", call.functionName());
        assertEquals(2, call.args().size());
    }

    @Test
    void callOnInlineLambda_parsesToApply() throws Exception {
        // Compound head → IrExpr.Apply, because there's no name to look up.
        IrExpr e = expr("(call (lambda ((x Int)) Int (+ x 1)) 5)");
        IrExpr.Apply app = (IrExpr.Apply) e;
        assertInstanceOf(IrExpr.Lambda.class, app.fn());
        assertEquals(1, app.args().size());
        assertEquals(5L, ((IrExpr.Lit) app.args().get(0)).value());
    }

    @Test
    void callOnNestedCall_parsesToApply() throws Exception {
        // Head is another (call ...) form — that whole thing is evaluated to get
        // a closure, which is then applied. Useful for returning lambdas.
        IrExpr e = expr("(call (call addN 5) 3)");
        IrExpr.Apply app = (IrExpr.Apply) e;
        assertInstanceOf(IrExpr.Call.class, app.fn());
        assertEquals(1, app.args().size());
    }

    @Test
    void callWithZeroArgs_parses() throws Exception {
        // (call f) — fn-only call, valid for thunks.
        IrExpr e = expr("(call f)");
        IrExpr.Call call = (IrExpr.Call) e;
        assertEquals("f", call.functionName());
        assertEquals(0, call.args().size());
    }

    // --- Sorts ---

    @Test
    void bareIdentifier_parsesAsNamedSort() throws Exception {
        IrSort s = sort("Int");
        assertInstanceOf(IrSort.Named.class, s);
        assertEquals("Int", ((IrSort.Named) s).name());
    }

    @Test
    void functionSort_parsesParamsAndReturn() throws Exception {
        IrSort s = sort("(function (Int) Int)");
        assertInstanceOf(IrSort.CallSig.class, s);
        IrSort.CallSig f = (IrSort.CallSig) s;
        assertEquals(1, f.paramSorts().size());
        assertEquals("Int", ((IrSort.Named) f.paramSorts().get(0)).name());
        assertEquals("Int", ((IrSort.Named) f.returnSort()).name());
    }

    @Test
    void functionSort_withMultipleParams_parses() throws Exception {
        IrSort s = sort("(function (Int Int Bool) Int)");
        IrSort.CallSig f = (IrSort.CallSig) s;
        assertEquals(3, f.paramSorts().size());
        assertEquals("Bool", ((IrSort.Named) f.paramSorts().get(2)).name());
    }

    @Test
    void functionSort_withZeroParams_parses() throws Exception {
        IrSort s = sort("(function () Int)");
        IrSort.CallSig f = (IrSort.CallSig) s;
        assertEquals(0, f.paramSorts().size());
        assertEquals("Int", ((IrSort.Named) f.returnSort()).name());
    }

    @Test
    void structSort_parsesNameAndFields() throws Exception {
        IrSort s = sort("(struct Point (x Int) (y Int))");
        assertInstanceOf(IrSort.Structural.class, s);
        IrSort.Structural st = (IrSort.Structural) s;
        assertEquals("Point", st.name());
        assertEquals(2, st.members().size());
        assertEquals("Int", ((IrSort.Named) st.members().get("x")).name());
        assertEquals("Int", ((IrSort.Named) st.members().get("y")).name());
    }

    @Test
    void structSort_withRefinedField_parses() throws Exception {
        IrSort s = sort("(struct Counter (n (refined Int (> self 0))))");
        IrSort.Structural st = (IrSort.Structural) s;
        assertEquals(1, st.members().size());
        assertInstanceOf(IrSort.Refined.class, st.members().get("n"));
    }

    @Test
    void structSort_canBeEmpty() throws Exception {
        IrSort s = sort("(struct Unit)");
        IrSort.Structural st = (IrSort.Structural) s;
        assertEquals("Unit", st.name());
        assertEquals(0, st.members().size());
    }

    @Test
    void recordForm_parsesFieldsAndValues() throws Exception {
        IrExpr e = expr("(record (x 1) (y 2))");
        IrExpr.Record r = (IrExpr.Record) e;
        assertEquals(2, r.members().size());
        assertEquals(1L, ((IrExpr.Lit) r.members().get("x")).value());
        assertEquals(2L, ((IrExpr.Lit) r.members().get("y")).value());
    }

    @Test
    void recordForm_withComputedAndNestedValues_parses() throws Exception {
        IrExpr e = expr("(record (sum (+ 1 2)) (inner (record (a 0))))");
        IrExpr.Record r = (IrExpr.Record) e;
        assertInstanceOf(IrExpr.BinOp.class, r.members().get("sum"));
        assertInstanceOf(IrExpr.Record.class, r.members().get("inner"));
    }

    @Test
    void fieldForm_parsesBaseAndFieldName() throws Exception {
        IrExpr e = expr("(field p x)");
        IrExpr.FieldAccess fa = (IrExpr.FieldAccess) e;
        assertEquals("x", fa.fieldName());
        assertInstanceOf(IrExpr.Var.class, fa.base());
    }

    @Test
    void fieldForm_canTraverseNestedRecords() throws Exception {
        // (field (field p inner) x)
        IrExpr e = expr("(field (field p inner) x)");
        IrExpr.FieldAccess outer = (IrExpr.FieldAccess) e;
        assertEquals("x", outer.fieldName());
        IrExpr.FieldAccess inner = (IrExpr.FieldAccess) outer.base();
        assertEquals("inner", inner.fieldName());
    }

    @Test
    void functionSort_canReturnAnotherFunctionSort() throws Exception {
        // (Int) -> ((Int) -> Int) — curried Int -> Int -> Int
        IrSort s = sort("(function (Int) (function (Int) Int))");
        IrSort.CallSig outer = (IrSort.CallSig) s;
        assertInstanceOf(IrSort.CallSig.class, outer.returnSort());
        IrSort.CallSig inner = (IrSort.CallSig) outer.returnSort();
        assertEquals(1, inner.paramSorts().size());
    }

    @Test
    void refinedSort_parsesWithPredicate() throws Exception {
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
    void module_withSingleFunctionDecl_parses() throws Exception {
        IrModule m = SexprParser.parseModule(
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
    void module_withMultipleFunctionDecls_parses() throws Exception {
        // Demonstrates parsing multiple defns
        IrModule m = SexprParser.parseModule(
                "(module m ((defn a ((x Int)) Int x) (defn b ((y Int)) Int y)) (call a 1))",
                "t.ptf");
        assertEquals(2, m.statements().size());
    }

    // --- Origin propagation ---

    @Test
    void origins_carryFileNameAndPosition() throws Exception {
        IrExpr e = expr("(+ 1 2)");
        IrExpr.BinOp op = (IrExpr.BinOp) e;
        assertTrue(op.origin().isPresent(), "origin should be set for parsed BinOp");
        assertTrue(op.origin().toString().contains("t.ptf"),
                "origin should carry source name; got: " + op.origin());
    }

    @Test
    void leftHandLit_carriesItsOwnOrigin() throws Exception {
        IrExpr e = expr("(+ 1 2)");
        IrExpr.BinOp op = (IrExpr.BinOp) e;
        assertTrue(op.left().origin().isPresent());
        assertTrue(op.right().origin().isPresent());
    }

    // --- Errors ---

    @Test
    void reservedWordAsVariable_throwsWithSuggestion() throws Exception {
        ParseException ex = assertThrows(ParseException.class, () -> expr("let"));
        assertTrue(ex.getMessage().contains("Reserved"),
                "error should mention reserved word; got: " + ex.getMessage());
    }

    @Test
    void mismatchedParen_throwsWithOrigin() throws Exception {
        ParseException ex = assertThrows(ParseException.class, () -> expr("(+ 1 2"));
        assertTrue(ex.getMessage().contains("t.ptf"),
                "error should include source name; got: " + ex.getMessage());
    }

    @Test
    void emptyMatch_throwsWithExplanation() throws Exception {
        ParseException ex = assertThrows(ParseException.class, () -> expr("(match 5)"));
        assertTrue(ex.getMessage().toLowerCase().contains("branch"),
                "error should mention missing branches; got: " + ex.getMessage());
    }

    @Test
    void unknownForm_throws() throws Exception {
        ParseException ex = assertThrows(ParseException.class, () -> expr("(unknownForm 1 2)"));
        assertTrue(ex.getMessage().contains("unknownForm")
                        || ex.getMessage().toLowerCase().contains("unknown"),
                "error should mention the unknown form; got: " + ex.getMessage());
    }
}
