package sibarum.pontif.parser;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Tests for sort-level {@code |} and {@code &} inside {@code [...]} in the
 * alt parser. Same-base unions/intersections normalize to a single
 * {@link IrSort.Refined}; cross-base stays as {@link IrSort.Union} or
 * {@link IrSort.Intersection}.
 */
class AltParserUnionIntersectionTest {

    /** Parses a single function decl whose param uses the given sort literal. */
    private static IrSort paramSort(String sortLiteral) throws ParseException {
        String src = "function f(x:" + sortLiteral + "):Int -> 0";
        IrModule m = AltParser.parseModule(src, "t");
        IrStmt.FunctionDecl fd = (IrStmt.FunctionDecl) m.statements().get(0);
        return fd.params().get(0).sort();
    }

    // --- Cross-base unions stay as IrSort.Union -----------------------------

    @Test
    void refinedParametricArg_composesInsideUnion() throws Exception {
        // REGRESSION: the bare-refined type-arg shorthand (`Stream[Int:pred]`)
        // only parsed at the top-level parametric path; wrapped in any bracket
        // sort the type-arg loop used `parseSort`, which stopped at `Int` and
        // demanded a comma at the `:` ("Expected COMMA but got COLON"). Now the
        // branch path parses args with `parseTypeArg`, so a refined Stream
        // composes in a union (and any nested sort).
        IrSort sort = paramSort("[Stream[Int:0 <= @ < 10] | Nothing]");
        IrSort.Union u = assertInstanceOf(IrSort.Union.class, sort);
        assertEquals(2, u.branches().size());
        IrSort.Named stream = assertInstanceOf(IrSort.Named.class, u.branches().get(0));
        assertEquals("Stream", stream.name());
        assertEquals(1, stream.typeArgs().size());
        IrSort.Refined elem = assertInstanceOf(IrSort.Refined.class, stream.typeArgs().get(0));
        assertEquals("Int", elem.name());
        assertEquals("Nothing", ((IrSort.Named) u.branches().get(1)).name());
    }

    @Test
    void crossBaseUnion_bareBareBare_yieldsUnion() throws Exception {
        IrSort sort = paramSort("[Int|Bool|Function]");
        IrSort.Union u = assertInstanceOf(IrSort.Union.class, sort);
        assertEquals(3, u.branches().size());
        assertEquals("Int", ((IrSort.Named) u.branches().get(0)).name());
        assertEquals("Bool", ((IrSort.Named) u.branches().get(1)).name());
        assertEquals("Function", ((IrSort.Named) u.branches().get(2)).name());
    }

    @Test
    void crossBaseUnion_bareRefinedMix_yieldsUnion() throws Exception {
        IrSort sort = paramSort("[Int|[Bool:true]]");
        IrSort.Union u = assertInstanceOf(IrSort.Union.class, sort);
        assertEquals(2, u.branches().size());
        assertInstanceOf(IrSort.Named.class, u.branches().get(0));
        assertInstanceOf(IrSort.Refined.class, u.branches().get(1));
    }

    @Test
    void crossBaseIntersection_yieldsIntersection() throws Exception {
        IrSort sort = paramSort("[Int & Bool]");
        IrSort.Intersection i = assertInstanceOf(IrSort.Intersection.class, sort);
        assertEquals(2, i.branches().size());
    }

    // --- Same-base normalization → single Refined ---------------------------

    @Test
    void sameBaseUnion_bareBare_normalizesToBareLike() throws Exception {
        // [Int|Int] — same base, both bare → Refined(Int, true | true)
        IrSort sort = paramSort("[Int|Int]");
        IrSort.Refined r = assertInstanceOf(IrSort.Refined.class, sort);
        assertEquals("Int", r.name());
        // Predicate: true OR true
        IrExpr.BinOp orExpr = assertInstanceOf(IrExpr.BinOp.class, r.predicate());
        assertEquals(IrExpr.Op.OR, orExpr.op());
    }

    @Test
    void sameBaseUnion_refinedRefined_normalizesToOrPredicate() throws Exception {
        // [[Int:0]|[Int:1]] — both Refined(Int, @==N) → Refined(Int, @==0 | @==1)
        IrSort sort = paramSort("[[Int:0]|[Int:1]]");
        IrSort.Refined r = assertInstanceOf(IrSort.Refined.class, sort);
        assertEquals("Int", r.name());
        IrExpr.BinOp orExpr = assertInstanceOf(IrExpr.BinOp.class, r.predicate());
        assertEquals(IrExpr.Op.OR, orExpr.op());
        // Left and right are each `@ == N` BinOps
        assertInstanceOf(IrExpr.BinOp.class, orExpr.left());
        assertInstanceOf(IrExpr.BinOp.class, orExpr.right());
    }

    @Test
    void sameBaseIntersection_normalizesToAndPredicate() throws Exception {
        // [[Int:@>0]&[Int:@<10]] → Refined(Int, @>0 & @<10)
        IrSort sort = paramSort("[[Int:@>0]&[Int:@<10]]");
        IrSort.Refined r = assertInstanceOf(IrSort.Refined.class, sort);
        assertEquals("Int", r.name());
        IrExpr.BinOp andExpr = assertInstanceOf(IrExpr.BinOp.class, r.predicate());
        assertEquals(IrExpr.Op.AND, andExpr.op());
    }

    @Test
    void sameBaseUnion_threeBranches_normalizesToNestedOrChain() throws Exception {
        // [[Int:0]|[Int:1]|[Int:2]] → Refined(Int, ((@==0 | @==1) | @==2))
        IrSort sort = paramSort("[[Int:0]|[Int:1]|[Int:2]]");
        IrSort.Refined r = (IrSort.Refined) sort;
        assertEquals("Int", r.name());
        IrExpr.BinOp outer = (IrExpr.BinOp) r.predicate();
        assertEquals(IrExpr.Op.OR, outer.op());
        // Left-folded: ((a OR b) OR c)
        IrExpr.BinOp inner = (IrExpr.BinOp) outer.left();
        assertEquals(IrExpr.Op.OR, inner.op());
    }

    // --- Predicate-level | and & still works the way it always did ---------

    @Test
    void predicateLevelOR_unchanged() throws Exception {
        // [Int:0|1|2] uses per-disjunct @== sugar — predicate-level, not
        // sort-level. Single Refined sort, no Union/Intersection wrapping.
        IrSort sort = paramSort("[Int:0|1|2]");
        IrSort.Refined r = assertInstanceOf(IrSort.Refined.class, sort);
        assertEquals("Int", r.name());
        // Predicate is a chain of @==N | @==M | @==K
        IrExpr.BinOp outer = (IrExpr.BinOp) r.predicate();
        assertEquals(IrExpr.Op.OR, outer.op());
    }
}
