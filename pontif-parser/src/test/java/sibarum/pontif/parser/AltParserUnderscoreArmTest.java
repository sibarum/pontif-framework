package sibarum.pontif.parser;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code _} default-arm desugar in {@link AltParser#parseMatch()}.
 *
 * <p>The parser computes the {@code _} arm's predicate as the complement of
 * the union of other arms' predicates over the scrutinee's sort, via the
 * {@code pontif-predicates} kernel. The resulting IR contains only explicit
 * refinement predicates.
 */
class AltParserUnderscoreArmTest {

    /**
     * Parses a function whose body is the given match expression. The scrutinee
     * is {@code n} of sort {@code Int} (visible via the function param), so the
     * parser can infer the scrutinee's sort and compute the {@code _} arm's
     * complement.
     */
    private static IrExpr.Match parseMatchInIntFunction(String matchSrc) throws ParseException {
        String source = "function test(n:Int):Int -> " + matchSrc;
        IrModule module = AltParser.parseModule(source, "test");
        IrStmt.FunctionDecl decl = (IrStmt.FunctionDecl) module.statements().get(0);
        IrExpr body = decl.body();
        // Structural-destructure desugar may wrap in an outer let — unwrap if so.
        if (body instanceof IrExpr.LetIn let) {
            return (IrExpr.Match) let.body();
        }
        return (IrExpr.Match) body;
    }

    /** Extracts the SymExpr predicate of a Refined arm pattern (round-trips via IrCompiler). */
    private static SymExpr predicateOf(IrSort pattern) throws Exception {
        IrSort.Refined r = assertInstanceOf(IrSort.Refined.class, pattern);
        return IrCompiler.compileSymExpr(r.predicate());
    }

    // --- Basic desugar -------------------------------------------------------

    @Test
    void underscore_desugars_to_complement_of_one_arm() throws Exception {
        IrExpr.Match m = parseMatchInIntFunction("match n [@<0] -> -1 _ -> 1");
        assertEquals(2, m.branches().size());

        IrSort.Refined defaultArm =
                assertInstanceOf(IrSort.Refined.class, m.branches().get(1).pattern());
        assertEquals("Int", defaultArm.name());
        // Complement of @<0 over Int is @>=0.
        SymExpr expected = SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(0));
        assertEquals(expected, predicateOf(defaultArm));
    }

    @Test
    void underscore_with_two_explicit_arms_gets_complement_of_union() throws Exception {
        IrExpr.Match m = parseMatchInIntFunction("match n [@<0] -> -1 [@==0] -> 0 _ -> 1");
        assertEquals(3, m.branches().size());

        // Complement of (@<0 | @==0) over Int is @>=1.
        SymExpr expected = SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(1));
        assertEquals(expected, predicateOf(m.branches().get(2).pattern()));
    }

    @Test
    void underscore_with_equality_arm_gets_two_half_lines() throws Exception {
        IrExpr.Match m = parseMatchInIntFunction("match n [@==0] -> 0 _ -> 1");
        assertEquals(2, m.branches().size());

        // Complement of @==0 over Int is @<=-1 | @>=1.
        SymExpr expected = SymExpr.or(
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.LE, SymExpr.lit(-1)),
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(1)));
        assertEquals(expected, predicateOf(m.branches().get(1).pattern()));
    }

    @Test
    void underscore_as_only_arm_covers_everything() throws Exception {
        IrExpr.Match m = parseMatchInIntFunction("match n _ -> 42");
        assertEquals(1, m.branches().size());

        // Complement of (no arms = empty set) = full domain = Bool true.
        assertEquals(SymExpr.bool(true), predicateOf(m.branches().get(0).pattern()));
    }

    // --- Order / multiplicity constraints ------------------------------------

    @Test
    void underscore_not_last_throws() {
        ParseException ex = assertThrows(ParseException.class, () ->
                parseMatchInIntFunction("match n _ -> 0 [@>0] -> 1"));
        assertTrue(ex.getMessage().toLowerCase().contains("after '_'"),
                () -> "expected message to mention '_' not last; got: " + ex.getMessage());
    }

    @Test
    void two_underscore_arms_throws() {
        ParseException ex = assertThrows(ParseException.class, () ->
                parseMatchInIntFunction("match n _ -> 0 _ -> 1"));
        assertTrue(ex.getMessage().toLowerCase().contains("after '_'"));
    }

    // --- Errors when the desugar can't be computed --------------------------

    @Test
    void underscore_with_unknown_scrutinee_sort_throws() {
        // Scrutinee is `(n+1)` — not a Var, so the parser can't infer its sort
        // for the desugar. Use explicit-base arms so the contextual-base check
        // doesn't fire first; the desugar's own validation is what triggers.
        ParseException ex = assertThrows(ParseException.class, () ->
                parseMatchInIntFunction("match (n+1) [Int:@<0] -> -1 _ -> 1"));
        assertTrue(ex.getMessage().toLowerCase().contains("infer scrutinee"),
                () -> "expected message about scrutinee sort; got: " + ex.getMessage());
    }
}
