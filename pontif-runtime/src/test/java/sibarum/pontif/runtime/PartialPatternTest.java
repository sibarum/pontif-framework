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
 * <p>The first fact survives at a parameter, and porting it found a silent misparse in a match
 * ARM: a braced `name:Sort` entry there is a POSITIONAL slot bound to the name (unlike a sort
 * position, where it is a one-field record shape), and for a SINGLE entry the grouping collapse
 * returned the bare sort and dropped the binder — so `[{x:Int}]` quietly became `[Int]`. It is a
 * parse error now, since the one-slot tuple pattern that would carry it is backlogged.
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

    /** The compile diagnostic, asserting the program was rejected. */
    private String reject(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "t.ptf");
        assertTrue(r instanceof PontifCompiler.CompileResult.Failed,
                "expected a compile rejection; got a compiling program");
        return ((PontifCompiler.CompileResult.Failed) r).error().text();
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
    void aSingleElementBracePattern_isRejected() {
        // This is what porting the file found. In a match arm — unlike a sort position, where
        // `{x:Int}` is a one-field record shape — a braced `name:Sort` entry is a POSITIONAL
        // slot tested as Sort and bound as name. For a single entry the grouping collapse then
        // returned the bare sort and threw the binder away, so `[{x:Int}]` silently became
        // `[Int]`: the arm tested something other than what was written, and the body's `x` was
        // unbound. One-element tuples are backlogged, so the honest answer is to say so.
        String err = reject("""
                let p = {x = 3}
                match p { [{x:Int}] -> 1  [_] -> 0 }
                """);
        assertTrue(err.contains("single-element brace pattern"),
                () -> "expected the one-slot pattern to be named; got: " + err);
        assertTrue(err.contains("let p.{x}"),
                () -> "the diagnostic should point at by-name decomposition; got: " + err);
    }

    @Test
    void aMultiElementBracePatternIsPositional_soARecordArmIsRejectedAsDead() {
        // With two entries there is no collapse and the documented pattern reading applies:
        // `[{x:Int, y:Int}]` is a two-slot TUPLE pattern that binds x and y. A record is not a
        // tuple, so no value of this scrutinee could reach the arm — and an arm that can never
        // match is a compile error (RULED James 2026-08-25), rather than a branch that quietly
        // loses to the default below it.
        String err = reject("""
                let p = {x = 3, y = 4}
                match p { [{x:Int, y:Int}] -> 1  [_] -> 0 }
                """);
        assertTrue(err.contains("can never match"),
                () -> "expected the dead arm to be named; got: " + err);
    }

    @Test
    void thatSamePatternFiresAgainstAnActualTuple() {
        assertEquals("3", value("""
                let p = {3, 4}
                match p { [{x:Int, y:Int}] -> x  [_] -> 0 }
                """));
    }
}
