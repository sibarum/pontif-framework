package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ast.record.RecordValue;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.AltParser;
import sibarum.pontif.parser.ParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice 1 — honest positional aggregates (tuples). Tuples are anonymous
 * positional aggregates riding the record substrate (sentinel "_tuple",
 * positional keys _0.._n). Source → AltParser → IrCompiler → IrInterpreter.
 */
class TupleTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = AltParser.parseModule(src, "t.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    @Test
    void tupleLiteral_roundtripsAsPositionalRecord() throws Exception {
        Object r = run("(1, true)");
        RecordValue rv = assertInstanceOf(RecordValue.class, r);
        assertEquals("_tuple", rv.typeName());
        assertEquals(1L, rv.members().get("_0"));
        assertEquals(true, rv.members().get("_1"));
    }

    @Test
    void tupleLiteral_displaysPositionally() throws Exception {
        assertEquals("(1, true)", String.valueOf(run("(1, true)")));
    }

    @Test
    void tupleLiteral_arityThree() throws Exception {
        assertEquals("(1, 2, 3)", String.valueOf(run("(1, 2, 3)")));
    }

    @Test
    void parens_withoutComma_stayGrouping() throws Exception {
        assertEquals(6L, run("(1 + 2) * 2"));
    }

    // --- destructuring (expression-level let) ---

    @Test
    void destructure_bindsComponentsPositionally() throws Exception {
        assertEquals(8L, run("let [(a, b)] = (3, 5) a + b"));
    }

    @Test
    void destructure_swapViaTuple() throws Exception {
        // Build a tuple, destructure, rebuild swapped, destructure again.
        assertEquals(1L, run("let [(a, b)] = (1, 2) let [(c, d)] = (b, a) d"));
    }

    // --- end-to-end: tuple param + match + tuple return ---

    @Test
    void swap_function_tupleParamAndReturn() throws Exception {
        String src = """
                function swap(p:[(Int, Bool)]):[(Bool, Int)] ->
                  match p { [(a, b)] -> (b, a) }
                let [(x, y)] = swap((1, true)) y
                """;
        assertEquals(1L, run(src));
    }

    // --- verdict C: `_` slot discard (occupies the slot, binds nothing) ---

    @Test
    void discard_inTuplePattern_bindsRemaining() throws Exception {
        assertEquals(4L, run("let [(a, _, c)] = (1, 2, 3) a + c"));
    }

    @Test
    void discard_inStructPattern_bindsRemaining() throws Exception {
        assertEquals(3L, run("struct Point(x:Int, y:Int)\nlet [Point(a, _)] = Point(3, 9) a"));
    }

    @Test
    void discardedSlot_isNotBound() throws Exception {
        // The discarded slot binds nothing — referencing `_` as a value fails.
        assertThrows(Exception.class,
                () -> run("let [(a, _)] = (1, 2) a + _"));
    }

    // --- verdict B: positional patterns must be arity-total ---

    @Test
    void partialTuplePattern_isRejected() {
        // [(a, b)] on a 3-tuple silently drops the third — lying by omission.
        assertThrows(ParseException.class,
                () -> run("let [(a, b)] = (1, 2, 3) a"));
    }

    @Test
    void partialStructPattern_isRejected() {
        // [Point(a)] lists 1 of 2 fields — the README:141 "subset" lie, now gone.
        assertThrows(ParseException.class,
                () -> run("struct Point(x:Int, y:Int)\nlet [Point(a)] = Point(1, 2) a"));
    }

    // --- positional projection: a value's component reads with `._N` (RULED
    //     2026-06-21 — the read-access sibling of destructuring; every aggregate
    //     has both forms, tuples no exception) ---

    @Test
    void valueLevelPositionalAccess_reads() throws Exception {
        assertEquals(1L, run("let p = (1, 2) p._0"));
        assertEquals(2L, run("let p = (1, 2) p._1"));
    }

    // --- per-component refinement in a tuple sort (whole-tuple refinement is Slice 1.5) ---

    @Test
    void perComponentRefinedTupleSort_acceptsAndRejects() throws Exception {
        String accept = """
                function f(p:[([Int:@>0], Bool)]):Int -> match p { [(a, b)] -> a }
                f((3, true))
                """;
        assertEquals(3L, run(accept));

        String reject = """
                function f(p:[([Int:@>0], Bool)]):Int -> match p { [(a, b)] -> a }
                f((-1, true))
                """;
        assertThrows(Exception.class, () -> run(reject));
    }

    @Test
    void wholeTupleRefinement_isRejected_relationshipNeedsAName() {
        // By design, not omission: a cross-component invariant is a relationship,
        // and a relationship is a named concept (a struct). Tuples carry only
        // independent per-component constraints. See parseTupleSortBody.
        assertThrows(ParseException.class,
                () -> run("function f(p:[(Int, Int):@._0 > @._1]):Int -> 0\n0"));
    }

    // --- paired per-component checking: each domain routes via the shared
    //     per-member path, no TupleDischarge. The reject cases prove the
    //     component obligation is actually checked (Int via the integer path,
    //     Decimal via the dense path) — through the same Refinements machinery
    //     a struct field uses, with zero tuple-specific code. ---

    @Test
    void decimalComponentRefinement_acceptsAndRejects() throws Exception {
        String accept = """
                function g(p:[([Decimal:@>0.0], Int)]):Int -> match p { [(a, b)] -> b }
                g((0.5, 2))
                """;
        assertEquals(2L, run(accept));

        String reject = """
                function g(p:[([Decimal:@>0.0], Int)]):Int -> match p { [(a, b)] -> b }
                g((-0.5, 2))
                """;
        assertThrows(Exception.class, () -> run(reject));
    }

    @Test
    void tupleReturn_componentRefinements_mirrorStructBehavior() throws Exception {
        // Tuples behave EXACTLY like structs here: return-position component
        // refinements are not compile-enforced for any aggregate today (the
        // same `function mk():[S(x:[Int:@>0])] -> S(-1)` is also accepted). This
        // pins the parity so tuples aren't "fixed" in isolation — closing this
        // gap is the aggregate-wide "make refinements bite" work (Slice 3), not
        // a tuple concern.
        assertEquals("(3, true)", String.valueOf(run(
                "function mk():[([Int:@>0], Bool)] -> (3, true)\nmk()")));
    }
}
