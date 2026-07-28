package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Stream queries (docs/stream-queries.md, Slice A). The `&s:[…]` bracket dispatches on the
 * KIND of its content: a transform arrow `(el)->…` is the per-element map (the iteration
 * multitool); a bare TYPE-SORT `[T:pred]` is a QUERY — a described, not-yet-run retrieval.
 * A terminal op chooses cardinality. Slice A implements `.first()` → the 0-or-1 scalar
 * terminal returning the honest-absence union `[Present(T)|Absent]` (§2.1), via a
 * stop-at-first-hit scan over the existing ACCUMULATOR + STOP engine primitives.
 *
 * <p>`.first()` is 0-or-1 by TAKING one, not by proving uniqueness (§2.1) — it does not care
 * whether more than one element matches. `Absent` is a DISTINCT nominal from
 * `Nothing`/`Break`/`Leaf`/`OutOfRange` (§4.1).
 */
class StreamQueryTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = AltParser.parseModule(src, "m.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        CompiledModule compiled = new IrCompiler(simp).compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    @Test void firstMatch_returnsPresent() throws Exception {
        assertEquals("Present{value: 2}", String.valueOf(run("""
                requires pontif.core.{Present, Absent}
                let s = {1, 2, 3}
                &s:[Int:@ == 2].first()""")));
    }

    @Test void noMatch_returnsAbsent() throws Exception {
        assertEquals("Absent{}", String.valueOf(run("""
                requires pontif.core.{Present, Absent}
                let s = {1, 2, 3}
                &s:[Int:@ == 9].first()""")));
    }

    @Test void takesTheLeadingMatch_ignoresLaterMatches() throws Exception {
        // The predicate matches 2, 3 and 4; `.first()` returns the leading match and does
        // not care that others exist (0-or-1 by taking, not by proving uniqueness).
        assertEquals("Present{value: 2}", String.valueOf(run("""
                requires pontif.core.{Present, Absent}
                let s = {1, 2, 3, 4}
                &s:[Int:@ > 1].first()""")));
    }

    @Test void emptyStream_returnsAbsent() throws Exception {
        assertEquals("Absent{}", String.valueOf(run("""
                requires pontif.core.{Present, Absent}
                let s = {5, 6, 7}
                &s:[Int:@ > 100].first()""")));
    }

    // --- struct-payload queries (the intended real use: find a record by a field) ---

    @Test void queryOverStructByField_returnsPresentRecord() throws Exception {
        assertEquals("Present{value: User{id: 2, name: \"b\"}}", String.valueOf(run("""
                requires pontif.core.{Present, Absent}
                struct User(id:Int, name:String)
                let s = {User(1, "a"), User(2, "b"), User(3, "c")}
                &s:[User:@.id == 2].first()""")));
    }

    @Test void queryOverStruct_noMatch_returnsAbsent() throws Exception {
        assertEquals("Absent{}", String.valueOf(run("""
                requires pontif.core.{Present, Absent}
                struct User(id:Int, name:String)
                let s = {User(1, "a"), User(2, "b")}
                &s:[User:@.id == 9].first()""")));
    }

    // --- nested-path predicate (@.a.b) — KEYED Slice 0 (commit 47331ae) validates these;
    // this exercises MATCHING one at runtime through Refinements. ---

    @Test void nestedPathPredicate_matchesAtRuntime() throws Exception {
        assertEquals("Present{value: User{id: 2, name: Name{first: \"b\"}}}", String.valueOf(run("""
                requires pontif.core.{Present, Absent}
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

    // --- error cases: a query must be terminated by a known terminal op (Slice A) ---

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
