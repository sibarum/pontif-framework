package sibarum.pontif.parser;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrSort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice 2 — bracket sorts + refinements.  Exercises {@link AltParser#parseSort()}
 * directly (same package access).  The integration tests in
 * {@code AltParserIntegrationTest} can't run sorts in isolation since they need
 * function declarations (Slice 3) for a usable program.
 */
class AltParserSortTest {

    private static IrSort sort(String src) throws ParseException {
        AltParser p = new AltParser(new AltLexer(src, "test").tokenize());
        return p.parseSort();
    }

    // --- bare-ident sugar ---

    @Test
    void bareIdent_isNamedSort() throws Exception {
        IrSort s = sort("Int");
        IrSort.Named n = assertInstanceOf(IrSort.Named.class, s);
        assertEquals("Int", n.name());
    }

    @Test
    void bracketedIdent_isAlsoNamedSort() throws Exception {
        // [Int] ≡ Int — bare-ident is sugar.
        IrSort s = sort("[Int]");
        IrSort.Named n = assertInstanceOf(IrSort.Named.class, s);
        assertEquals("Int", n.name());
    }

    @Test
    void customNamedSort_works() throws Exception {
        // Any uppercase-ident is a valid sort name — alias resolution happens later.
        IrSort.Named n = assertInstanceOf(IrSort.Named.class, sort("Point"));
        assertEquals("Point", n.name());
    }

    // --- refined sorts (canonical form) ---

    @Test
    void refined_explicitComparison_keepsPredicateAsIs() throws Exception {
        // [Int:@>0] — predicate has top-level comparison, no sugar applied.
        IrSort.Refined r = assertInstanceOf(IrSort.Refined.class, sort("[Int:@>0]"));
        assertEquals("Int", r.name());
        IrExpr.BinOp bop = assertInstanceOf(IrExpr.BinOp.class, r.predicate());
        assertEquals(IrExpr.Op.GT, bop.op());
        assertInstanceOf(IrExpr.SelfRef.class, bop.left());
        IrExpr.Lit lit = assertInstanceOf(IrExpr.Lit.class, bop.right());
        assertEquals(0L, lit.value());
    }

    @Test
    void refined_allComparisons() throws Exception {
        assertEquals(IrExpr.Op.LT, refinedOp("[Int:@<0]"));
        assertEquals(IrExpr.Op.LE, refinedOp("[Int:@<=0]"));
        assertEquals(IrExpr.Op.GT, refinedOp("[Int:@>0]"));
        assertEquals(IrExpr.Op.GE, refinedOp("[Int:@>=0]"));
        assertEquals(IrExpr.Op.EQ, refinedOp("[Int:@==0]"));
        assertEquals(IrExpr.Op.NE, refinedOp("[Int:@!=0]"));
    }

    private static IrExpr.Op refinedOp(String src) throws ParseException {
        IrSort.Refined r = (IrSort.Refined) sort(src);
        return ((IrExpr.BinOp) r.predicate()).op();
    }

    // --- implicit @==EXPR sugar ---

    @Test
    void singletonLiteral_getsImplicitAtEquals() throws Exception {
        // [Int:42] ≡ [Int:@==42]
        IrSort.Refined r = (IrSort.Refined) sort("[Int:42]");
        assertEquals("Int", r.name());
        IrExpr.BinOp bop = (IrExpr.BinOp) r.predicate();
        assertEquals(IrExpr.Op.EQ, bop.op());
        assertInstanceOf(IrExpr.SelfRef.class, bop.left());
        assertEquals(42L, ((IrExpr.Lit) bop.right()).value());
    }

    @Test
    void boolSingletonTrue_getsImplicitAtEquals() throws Exception {
        // [Bool:true] ≡ [Bool:@==true]
        IrSort.Refined r = (IrSort.Refined) sort("[Bool:true]");
        assertEquals("Bool", r.name());
        IrExpr.BinOp bop = (IrExpr.BinOp) r.predicate();
        assertEquals(IrExpr.Op.EQ, bop.op());
        assertInstanceOf(IrExpr.SelfRef.class, bop.left());
        assertEquals(true, ((IrExpr.Bool) bop.right()).value());
    }

    @Test
    void boolSingletonFalse_getsImplicitAtEquals() throws Exception {
        IrSort.Refined r = (IrSort.Refined) sort("[Bool:false]");
        IrExpr.BinOp bop = (IrExpr.BinOp) r.predicate();
        assertEquals(IrExpr.Op.EQ, bop.op());
        assertEquals(false, ((IrExpr.Bool) bop.right()).value());
    }

    @Test
    void expressionPredicate_getsImplicitAtEquals() throws Exception {
        // [Int:n*2] ≡ [Int:@==n*2]
        // Critically: this was the bug that prompted the syntax overhaul —
        // [n*2] used to be parsed as Refined("n", BinOp(MUL, SelfRef, 2)),
        // which is nonsense. The new form is unambiguous.
        IrSort.Refined r = (IrSort.Refined) sort("[Int:n*2]");
        assertEquals("Int", r.name());
        IrExpr.BinOp bop = (IrExpr.BinOp) r.predicate();
        assertEquals(IrExpr.Op.EQ, bop.op());
        assertInstanceOf(IrExpr.SelfRef.class, bop.left());
        IrExpr.BinOp rhs = (IrExpr.BinOp) bop.right();
        assertEquals(IrExpr.Op.MUL, rhs.op());
        assertEquals("n", ((IrExpr.Var) rhs.left()).name());
        assertEquals(2L, ((IrExpr.Lit) rhs.right()).value());
    }

    // --- per-disjunct / per-conjunct sugar ---

    @Test
    void unionOfLiterals_eachDisjunctGetsAtEquals() throws Exception {
        // [Int:0|1] ≡ [Int:@==0 | @==1]
        IrSort.Refined r = (IrSort.Refined) sort("[Int:0|1]");
        IrExpr.BinOp top = (IrExpr.BinOp) r.predicate();
        assertEquals(IrExpr.Op.OR, top.op());
        IrExpr.BinOp left = (IrExpr.BinOp) top.left();
        assertEquals(IrExpr.Op.EQ, left.op());
        assertInstanceOf(IrExpr.SelfRef.class, left.left());
        assertEquals(0L, ((IrExpr.Lit) left.right()).value());
        IrExpr.BinOp right = (IrExpr.BinOp) top.right();
        assertEquals(IrExpr.Op.EQ, right.op());
        assertEquals(1L, ((IrExpr.Lit) right.right()).value());
    }

    @Test
    void mixedUnion_comparisonAndLiteral() throws Exception {
        // [Int:@<0 | 5] — left already a comparison, right gets sugar.
        // Cooked: @<0 | @==5
        IrSort.Refined r = (IrSort.Refined) sort("[Int:@<0 | 5]");
        IrExpr.BinOp top = (IrExpr.BinOp) r.predicate();
        assertEquals(IrExpr.Op.OR, top.op());
        IrExpr.BinOp left = (IrExpr.BinOp) top.left();
        assertEquals(IrExpr.Op.LT, left.op());
        IrExpr.BinOp right = (IrExpr.BinOp) top.right();
        assertEquals(IrExpr.Op.EQ, right.op());
    }

    @Test
    void conjunctionOfComparisons_keptAsIs() throws Exception {
        // [Int:@>0 & @<10] — both operands are comparisons, no sugar applied.
        IrSort.Refined r = (IrSort.Refined) sort("[Int:@>0 & @<10]");
        IrExpr.BinOp top = (IrExpr.BinOp) r.predicate();
        assertEquals(IrExpr.Op.AND, top.op());
        assertEquals(IrExpr.Op.GT, ((IrExpr.BinOp) top.left()).op());
        assertEquals(IrExpr.Op.LT, ((IrExpr.BinOp) top.right()).op());
    }

    @Test
    void deeplyNestedUnion_eachLeafGetsSugar() throws Exception {
        // [Int:0|1|2] left-associates as (0|1)|2.
        // After sugar: (@==0 | @==1) | @==2
        IrSort.Refined r = (IrSort.Refined) sort("[Int:0|1|2]");
        IrExpr.BinOp top = (IrExpr.BinOp) r.predicate();
        assertEquals(IrExpr.Op.OR, top.op());
        IrExpr.BinOp left = (IrExpr.BinOp) top.left();
        assertEquals(IrExpr.Op.OR, left.op());
        // Every leaf should be an EQ over SelfRef
        assertEquals(IrExpr.Op.EQ, ((IrExpr.BinOp) left.left()).op());
        assertEquals(IrExpr.Op.EQ, ((IrExpr.BinOp) left.right()).op());
        assertEquals(IrExpr.Op.EQ, ((IrExpr.BinOp) top.right()).op());
    }

    @Test
    void negativeLiteralsInUnion_work() throws Exception {
        // [Int:-1|0|1] — signed integers in the union sugar.
        IrSort.Refined r = (IrSort.Refined) sort("[Int:-1|0|1]");
        // Pull out the leaf literals from the cooked tree.
        IrExpr.BinOp top = (IrExpr.BinOp) r.predicate();
        IrExpr.BinOp leftOr = (IrExpr.BinOp) top.left();
        long a = ((IrExpr.Lit) ((IrExpr.BinOp) leftOr.left()).right()).value();
        long b = ((IrExpr.Lit) ((IrExpr.BinOp) leftOr.right()).right()).value();
        long c = ((IrExpr.Lit) ((IrExpr.BinOp) top.right()).right()).value();
        assertEquals(-1L, a);
        assertEquals(0L, b);
        assertEquals(1L, c);
    }

    // --- error cases ---

    @Test
    void emptyBrackets_areError() {
        assertThrows(ParseException.class, () -> sort("[]"));
    }

    @Test
    void missingPredicateAfterColon_isError() {
        // [Int:] — colon must be followed by an expression.
        assertThrows(ParseException.class, () -> sort("[Int:]"));
    }

    // --- function sorts (Slice 7) ---

    @Test
    void functionSort_anonymousParams() throws Exception {
        // [Method(Int):Int] — single anonymous param returning Int.
        IrSort.Method f = (IrSort.Method) sort("[Method(Int):Int]");
        assertEquals(1, f.paramSorts().size());
        assertEquals("Int", ((IrSort.Named) f.paramSorts().get(0)).name());
        assertEquals("Int", ((IrSort.Named) f.returnSort()).name());
    }

    @Test
    void functionSort_multipleAnonymousParams() throws Exception {
        // [Method(Int, Bool):Int]
        IrSort.Method f = (IrSort.Method) sort("[Method(Int, Bool):Int]");
        assertEquals(2, f.paramSorts().size());
        assertEquals("Int", ((IrSort.Named) f.paramSorts().get(0)).name());
        assertEquals("Bool", ((IrSort.Named) f.paramSorts().get(1)).name());
    }

    @Test
    void functionSort_nestedFunctionReturn() throws Exception {
        // [Method(Int):[Method(Int):Int]] — curried-style sort.
        IrSort.Method f = (IrSort.Method) sort("[Method(Int):[Method(Int):Int]]");
        IrSort.Method inner = (IrSort.Method) f.returnSort();
        assertEquals(1, inner.paramSorts().size());
        assertEquals("Int", ((IrSort.Named) inner.returnSort()).name());
    }

    @Test
    void functionSort_positionalParams_haveNoNames() throws Exception {
        // Positional sorts keep the empty-names (positional) form.
        IrSort.Method f = (IrSort.Method) sort("[Method(Int, Bool):Int]");
        assertTrue(f.paramNames().isEmpty(), "positional sort carries no names");
    }

    @Test
    void functionSort_namedParams_carryNames() throws Exception {
        // [Method(i:Int, j:Bool):Int] — named params now parse and carry their names
        // (WAR(dependent-sorts) slice 1: IrSort.Method holds param names).
        IrSort.Method f = (IrSort.Method) sort("[Method(i:Int, j:Bool):Int]");
        assertEquals(2, f.paramSorts().size());
        assertEquals(java.util.List.of("i", "j"), f.paramNames());
        assertEquals("Int", ((IrSort.Named) f.paramSorts().get(0)).name());
        assertEquals("Bool", ((IrSort.Named) f.paramSorts().get(1)).name());
    }

    @Test
    void functionSort_mixedNamedAndPositional_isError() {
        // Name all parameters or none — mixing is rejected.
        ParseException ex = assertThrows(ParseException.class,
                () -> sort("[Method(i:Int, Bool):Int]"));
        assertTrue(ex.getMessage().contains("mixes named and positional"),
                "expected mixed-params error; got: " + ex.getMessage());
    }

    @Test
    void contextualBaseForm_outsideMatch_isError() {
        // [@>0] without an enclosing match context — no base available.
        // (Slice 4 added contextual-base inference, but only inside match arms.)
        ParseException ex = assertThrows(ParseException.class, () -> sort("[@>0]"));
        assertTrue(ex.getMessage().contains("no contextual base"),
                "expected an error mentioning the missing context; got: " + ex.getMessage());
    }
}
