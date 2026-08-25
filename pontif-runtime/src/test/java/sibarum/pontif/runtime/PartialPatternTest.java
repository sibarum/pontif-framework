package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Naming only SOME of a value's fields — what it means at each site that asks.
 *
 * <p>Ported from the S-expression syntax when that parser was decommissioned. The original
 * pinned two facts through inline structural sorts: that a pattern listing fewer fields than
 * the value still matches (subset semantics), and that an inline struct NAME in such a
 * pattern was cosmetic, since an inline S-expr sort was never registered and so had no
 * nominal type to claim. The second scenario does not survive the port and should not: an
 * inline unregistered type name is not spellable in Pontif — a name in a sort position must
 * resolve, which {@code SortChecker.checkSortNames} now enforces at the declaration.
 *
 * <p>The first fact survives at a parameter, and porting it found a gap: the by-name
 * anonymous shape `[{x:Int}]` never matches as a match ARM — not for a wider record, and not
 * for an exactly shaped one — while the positional face `[{Int, Int}]` does, and while the
 * by-name face is judged member-wise everywhere a CLAIM is made (docs/soundness-holes.md
 * family 1). That dead arm is pinned below as a known limitation, deliberately not as
 * intended behavior.
 */
class PartialPatternTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String src) {
        return runner.run(compiler.compile(src, "t.ptf"), Engine.INTERPRETER);
    }

    private String value(String src) {
        RunResult r = run(src);
        assertFalse(r.isError(), () -> "expected success; got: " + r.text());
        return r.text();
    }

    // --- naming some fields of a value you hold -----------------------------------

    @Test
    void decompositionBindsOnlyTheFieldsItNames() {
        // The value has x AND y; the decomposition names only x, and y is simply not bound.
        assertEquals("30", value("""
                let p = {x = 3, y = 4}
                let p.{x}
                x * 10
                """));
    }

    @Test
    void decompositionOverAStructIsTheSame() {
        // A struct is decomposed by name like any aggregate — the projection does not care
        // that the value carries more than was asked for.
        assertEquals("30", value("""
                struct Point(x:Int, y:Int)
                let p = Point(3, 4)
                let p.{x}
                x * 10
                """));
    }

    @Test
    void namingAFieldTheValueLacksIsRejected() {
        // The S-expr version of this failed at RUNTIME. Naming a key the source's sort does
        // not have is caught at parse time now, and the message names the available fields —
        // a by-name projection is honest-partial, but an unknown key is a lie.
        PontifCompiler.CompileResult r = compiler.compile("""
                let p = {x = 3, y = 4}
                let p.{z}
                z
                """, "t.ptf");
        String err = ((PontifCompiler.CompileResult.Failed) r).error().text();
        assertTrue(err.contains("no member 'z'"), () -> "expected the unknown key named; got: " + err);
    }

    // --- naming some fields as a declared SORT ------------------------------------

    @Test
    void aPartialShapeParameterAcceptsAWiderRecord() {
        // Subset semantics at the call boundary: `[{x:Int}]` asks for something with an x.
        assertEquals("7", value("""
                function justX(p:[{x:Int}]):Int -> p.x
                justX({x = 7, y = 99})
                """));
    }

    @Test
    void aPartialShapeParameterAcceptsAStruct() {
        assertEquals("7", value("""
                struct Point(x:Int, y:Int)
                function justX(p:[{x:Int}]):Int -> p.x
                justX(Point(7, 99))
                """));
    }

    @Test
    void aPositionalShapeMatchesAsAnArm() {
        // The positional face works as a match arm, so the arm machinery handles anonymous
        // shapes in principle — which is what makes the by-name case below a gap rather than
        // a design choice.
        assertEquals("1", value("""
                let p = {3, 4}
                match p { [{Int, Int}] -> 1  [_] -> 0 }
                """));
    }

    @Test
    void byNameShapeAsAMatchArm_neverMatches_KNOWN_LIMITATION() {
        // Found by porting this file. `[{x:Int}]` is a real claim everywhere else — it is
        // judged member-wise at a let, a field, and a parameter (docs/soundness-holes.md
        // family 1, which fixed exactly this asymmetry on the CLAIM side, for both faces).
        // As a match ARM it never fires: not for a wider record, and not even for an exactly
        // shaped one. The arm is silently dead, and `[_]` covers for it so totality is happy.
        //
        // Pinned as a limitation, NOT as correct behavior — the lesson of family 5, where a
        // test asserting an engine divergence made the divergence look intended for months.
        // When the by-name arm starts matching, this test SHOULD fail; delete it then and
        // assert the match instead.
        assertEquals("0", value("""
                let p = {x = 3}
                match p { [{x:Int}] -> 1  [_] -> 0 }
                """));
        assertEquals("0", value("""
                let p = {x = 3, y = 4}
                match p { [{x:Int}] -> 1  [_] -> 0 }
                """));
    }
}
