package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.PontifParser;
import sibarum.pontif.parser.ParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Stream queries (docs/stream-queries.md, Slice A). The `&s:[…]` bracket dispatches on the
 * KIND of its content: a transform arrow `(el)->…` is the per-element map (the iteration
 * multitool); a bare TYPE-SORT `[T:pred]` is a QUERY — a described, not-yet-run retrieval.
 * A terminal op chooses cardinality. `.first()` → the 0-or-1 scalar terminal returning the
 * union `[T | Absent]` (the found element itself, or `Absent` — NO `Present` wrapper), via a
 * stop-at-first-hit scan over the existing ACCUMULATOR + STOP engine primitives.
 *
 * <p>`.first()` is 0-or-1 by TAKING one, not by proving uniqueness (§2.1) — it does not care
 * whether more than one element matches. Because the element rides unwrapped, consuming the
 * result is an ordinary type-guard match (`[T] -> … [_] -> …`) — no imported-struct
 * destructure, so no linker pass needed. `Absent` is a DISTINCT nominal from
 * `Nothing`/`Break`/`Leaf`/`OutOfRange` (§4.1).
 */
class StreamQueryTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = PontifParser.parseModule(src, "m.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        CompiledModule compiled = new IrCompiler(simp).compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    // Naming an imported nominal (`[Absent]`) as a match-arm SORT needs pontif.core's struct
    // registry merged in — which the ModuleLinker does but the bare parse+compile above does
    // not. (This is sort-name resolution, NOT a destructure — the unwrapped result still needs
    // no DestructureResolver.) So the tests that write an explicit `[Absent]` arm go through
    // the full PontifCompiler pipeline, exactly as a real multi-file program would.
    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String runLinked(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "m.ptf");
        PontifRunner.RunResult res = runner.run(r, PontifRunner.Engine.INTERPRETER);
        org.junit.jupiter.api.Assertions.assertFalse(
                res.isError(), () -> "expected success; got: " + res.text());
        return res.text();
    }

    @Test void firstMatch_returnsTheElement() throws Exception {
        // A hit returns the found element ITSELF — no Present wrapper.
        assertEquals("2", String.valueOf(run("""
                requires pontif.core.{Absent}
                let s = {1, 2, 3}
                &s:[Int:@ == 2].first()""")));
    }

    @Test void noMatch_returnsAbsent() throws Exception {
        assertEquals("Absent{}", String.valueOf(run("""
                requires pontif.core.{Absent}
                let s = {1, 2, 3}
                &s:[Int:@ == 9].first()""")));
    }

    @Test void takesTheLeadingMatch_ignoresLaterMatches() throws Exception {
        // The predicate matches 2, 3 and 4; `.first()` returns the leading match and does
        // not care that others exist (0-or-1 by taking, not by proving uniqueness).
        assertEquals("2", String.valueOf(run("""
                requires pontif.core.{Absent}
                let s = {1, 2, 3, 4}
                &s:[Int:@ > 1].first()""")));
    }

    @Test void emptyStream_returnsAbsent() throws Exception {
        assertEquals("Absent{}", String.valueOf(run("""
                requires pontif.core.{Absent}
                let s = {5, 6, 7}
                &s:[Int:@ > 100].first()""")));
    }

    // --- struct-payload queries (the intended real use: find a record by a field) ---

    @Test void queryOverStructByField_returnsTheRecord() throws Exception {
        // The found record itself, unwrapped.
        assertEquals("User{id: 2, name: \"b\"}", String.valueOf(run("""
                requires pontif.core.{Absent}
                struct User(id:Int, name:String)
                let s = {User(1, "a"), User(2, "b"), User(3, "c")}
                &s:[User:@.id == 2].first()""")));
    }

    @Test void queryOverStruct_noMatch_returnsAbsent() throws Exception {
        assertEquals("Absent{}", String.valueOf(run("""
                requires pontif.core.{Absent}
                struct User(id:Int, name:String)
                let s = {User(1, "a"), User(2, "b")}
                &s:[User:@.id == 9].first()""")));
    }

    // --- nested-path predicate (@.a.b) — KEYED Slice 0 (commit 47331ae) validates these;
    // this exercises MATCHING one at runtime through Refinements. ---

    @Test void nestedPathPredicate_matchesAtRuntime() throws Exception {
        assertEquals("User{id: 2, name: Name{first: \"b\"}}", String.valueOf(run("""
                requires pontif.core.{Absent}
                struct Name(first:String)
                struct User(id:Int, name:Name)
                let s = {User(1, Name("a")), User(2, Name("b"))}
                &s:[User:@.name.first == "b"].first()""")));
    }

    // --- `.all()` — the 0-or-many terminal: materialize the query to Stream[T] ---

    @Test void all_returnsEveryMatch_asStream() throws Exception {
        assertEquals("{2, 3, 4}", String.valueOf(run("""
                let s = {1, 2, 3, 4}
                &s:[Int:@ > 1].all()""")));
    }

    @Test void all_noMatch_returnsEmptyStream() throws Exception {
        assertEquals("{}", String.valueOf(run("""
                let s = {1, 2, 3}
                &s:[Int:@ > 100].all()""")));
    }

    @Test void all_dropsNonMatching_keepsOrder() throws Exception {
        // Non-matching elements are dropped, matching ones keep source order (a select,
        // not a map — the emitted value is the bare element).
        assertEquals("{1, 2, 3}", String.valueOf(run("""
                let s = {1, 9, 2, 8, 3}
                &s:[Int:@ < 5].all()""")));
    }

    @Test void all_overStructByField() throws Exception {
        assertEquals("{User{id: 3, name: \"c\"}, User{id: 4, name: \"d\"}}", String.valueOf(run("""
                struct User(id:Int, name:String)
                let s = {User(1, "a"), User(2, "b"), User(3, "c"), User(4, "d")}
                &s:[User:@.id > 2].all()""")));
    }

    // --- the result is CONSUMABLE: match on [T | Absent] (the intended usage) ---

    // The element rides UNWRAPPED, so consumption is an ordinary type-guard match — NO
    // imported-struct destructure, hence the plain (non-linked) harness suffices. The
    // `.first()` result infers as the union `[T | Absent]` (single-ACCUMULATOR Iterate
    // result-sort inference), so a two-arm `[T]`/`[Absent]` match is provably exhaustive —
    // NO `[_]` catch-all needed.

    @Test void matchOnHit_usesTheElement() {
        assertEquals("2", runLinked("""
                requires pontif.core.{Absent}
                let s = {1, 2, 3}
                let r = &s:[Int:@ == 2].first()
                match r
                  [Int]    -> r
                  [Absent] -> 0 - 1"""));
    }

    @Test void matchOnMiss_takesTheAbsentArm() {
        assertEquals("-1", runLinked("""
                requires pontif.core.{Absent}
                let s = {1, 2, 3}
                let r = &s:[Int:@ == 9].first()
                match r
                  [Int]    -> r
                  [Absent] -> 0 - 1"""));
    }

    @Test void matchOnHit_overStruct_pullsFieldOut() {
        assertEquals("\"b\"", runLinked("""
                requires pontif.core.{Absent}
                struct User(id:Int, name:String)
                let s = {User(1, "a"), User(2, "b"), User(3, "c")}
                let r = &s:[User:@.id == 2].first()
                match r
                  [User(uid, uname)] -> uname
                  [Absent]           -> "none"
                """));
    }

    @Test void twoArmMatch_isExhaustiveWithoutCatchAll() {
        // The narrowing payoff: `[T]`/`[Absent]` with NO `[_]` compiles (provably total),
        // because `.first()` now infers as the union `[Int | Absent]`.
        assertEquals("20", runLinked("""
                requires pontif.core.{Absent}
                let s = {5, 10, 15}
                let r = &s:[Int:@ > 8].first()
                match r
                  [Int]    -> r * 2
                  [Absent] -> 0"""));
    }

    // --- disambiguation: the SAME stream, an arrow still maps, a type-sort queries ---

    @Test void arrowSpreadStillMaps_typeSortQueries() throws Exception {
        // The fork the whole feature rests on (docs/stream-queries.md §1): an arrow body
        // is the per-element map; a bare type-sort is a query.
        assertEquals("{2, 4, 6}", String.valueOf(run("""
                let s = {1, 2, 3}
                &s:[ (el:Int) -> el * 2 ]""")));
        assertEquals("{2, 3}", String.valueOf(run("""
                let s = {1, 2, 3}
                &s:[Int:@ > 1].all()""")));
    }

    // --- predicate conjunction inside a query ---

    @Test void conjunctionPredicate() throws Exception {
        assertEquals("{2, 3}", String.valueOf(run("""
                let s = {1, 2, 3, 4}
                &s:[Int:@ > 1 & @ < 4].all()""")));
    }

    // --- error cases: a query must be terminated by a known terminal op (Slice A) ---

    @Test void zipQuery_isParseError() {
        // A multi-source `(&a, &b):[T:pred]` query is not supported (docs/stream-queries.md).
        assertThrows(ParseException.class, () -> run("""
                let a = {1, 2, 3}
                let b = {4, 5, 6}
                (&a, &b):[Int:@ > 1].first()"""));
    }


    @Test void missingTerminal_isParseError() {
        // Slice A does not yet reify a standalone Query value, and the Stream-valued
        // filter face (keyed.md) is unbuilt — so an un-terminated query is a parse error.
        assertThrows(ParseException.class, () -> run("""
                let s = {1, 2, 3}
                &s:[Int:@ == 2]"""));
    }

    @Test void unknownTerminal_isParseError() {
        assertThrows(ParseException.class, () -> run("""
                let s = {1, 2, 3}
                &s:[Int:@ == 2].peek()"""));
    }
}
