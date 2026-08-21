package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end coverage for sort aliases ({@code let X:Type[...]}) combined with
 * traits, trait default methods, refinements, and generics — the feature
 * cross-products that were previously untested and where a few silently
 * detached or produced baffling errors.
 *
 * <p>Group A pins concrete values (a failure is a real regression). Group B
 * pins error-message QUALITY (a diagnostic the user can act on). Group C
 * documents currently-unsupported combinations so a future change that starts
 * accepting them trips this test and gets a deliberate decision.
 */
class AliasTraitCombinationTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        CompileResult r = compiler.compile(src, "combo.ptf");
        CompileResult.Compiled c = assertInstanceOf(
                CompileResult.Compiled.class, r, () -> "expected compile success; got " + r);
        PontifRunner.RunResult rr = runner.run(c.program(), Engine.INTERPRETER);
        assertTrue(!rr.isError(), () -> "run error: " + rr.text());
        return rr.text();
    }

    private String reject(String src) {
        CompileResult r = compiler.compile(src, "combo.ptf");
        if (r instanceof CompileResult.Failed f) return f.error().text();
        // A compile that "succeeds" may still error at runtime — surface either.
        PontifRunner.RunResult rr = runner.run(((CompileResult.Compiled) r).program(), Engine.INTERPRETER);
        if (rr.isError()) return rr.text();
        return fail("expected a rejection but it ran to: " + rr.text());
    }

    // ===== Group A — value-pinned working combinations ======================

    @Test void aliasAsStructFieldType() {
        assertEquals("3", run("""
                let Coord:Type[Int]
                struct Point(x:Coord, y:Coord)
                Point(3, 4).x"""));
    }

    @Test void aliasUnionAsParam_dispatches() {
        assertEquals("5", run("""
                struct A(v:Int)
                struct B(v:Int)
                let AB:Type[A | B]
                function f(ab:AB):Int -> ab.v
                f(A(5))"""));
    }

    @Test void aliasRefinedAsParam() {
        assertEquals("10", run("""
                let Pos:Type[[Int:@>0]]
                function dbl(n:Pos):Int -> n * 2
                dbl(5)"""));
    }

    @Test void aliasInTraitContractReturn() {
        assertEquals("4", run("""
                let Score:Type[Int]
                trait Rated{ rate:[Method():Score] }
                struct Movie(stars:Int)
                assign trait Movie:Rated { rate():Score -> this.stars }
                Movie(4).rate()"""));
    }

    @Test void aliasInTraitDefaultReturn() {
        assertEquals("4", run("""
                let Score:Type[Int]
                trait Rated{ rate():Score -> this.stars }
                struct Movie(stars:Int)
                assign trait Movie:Rated { }
                Movie(4).rate()"""));
    }

    @Test void aliasInTraitDefaultParam() {
        assertEquals("15", run("""
                let Amt:Type[Int]
                trait Addable{ plus(d:Amt):Int -> this.v + d }
                struct Box(v:Int)
                assign trait Box:Addable { }
                Box(10).plus(5)"""));
    }

    @Test void transitiveAliasIntoRefinement() {
        assertEquals("5", run("""
                let A:Type[[Int:@>0]]
                let B:Type[A]
                function f(n:B):Int -> n + 1
                f(4)"""));
    }

    // --- assign trait to a STRUCT ALIAS: fixed (previously "No method") -----

    @Test void assignTraitToStructAlias_attachesToStruct() {
        assertEquals("7", run("""
                struct P(x:Int, y:Int)
                let Pt:Type[P]
                trait Summable{ sum:[Method():Int] }
                assign trait Pt:Summable { sum():Int -> this.x + this.y }
                P(3, 4).sum()"""));
    }

    @Test void assignTraitToChainedStructAlias_attachesToStruct() {
        assertEquals("7", run("""
                struct P(x:Int, y:Int)
                let Pt:Type[P]
                let Pt2:Type[Pt]
                trait Summable{ sum:[Method():Int] }
                assign trait Pt2:Summable { sum():Int -> this.x + this.y }
                P(3, 4).sum()"""));
    }

    /** The playground showcase runs end-to-end and matches its hand-computed value. */
    @Test void showcaseExampleRuns() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "..", "pontif-playground", "examples", "trait-on-sort-alias.ptf"));
        assertEquals("78", run(src));
    }

    // ===== Group B — diagnostic quality ====================================

    @Test void matchArm_bareConstructor_getsBracketHint() {
        String err = reject("""
                struct A(v:Int)
                function f(a:A):Int -> match a {
                  A(v) -> v
                }
                f(A(3))""");
        assertTrue(err.contains("bracket-sort"),
                () -> "expected a bracket-sort hint; got: " + err);
    }

    @Test void matchArm_bareConstructor_oneLine_getsBracketHint() {
        String err = reject("""
                struct A(v:Int)
                function f(a:A):Int -> match a { A(v) -> v }
                f(A(3))""");
        assertTrue(err.contains("bracket-sort"),
                () -> "expected a bracket-sort hint; got: " + err);
    }

    @Test void assignTraitToUnionAlias_rejectedWithGuidance() {
        String err = reject("""
                struct A(v:Int)
                struct B(v:Int)
                let AB:Type[A | B]
                trait Named{ nm:[Method():Int] }
                assign trait AB:Named { nm():Int -> this.v }
                A(3).nm()""");
        assertTrue(err.contains("sort alias") && err.contains("union"),
                () -> "expected a union-alias assign-trait rejection; got: " + err);
    }

    @Test void assignTraitToRefinedAlias_rejectedWithGuidance() {
        String err = reject("""
                let Pos:Type[[Int:@>0]]
                trait Named{ nm:[Method():Int] }
                assign trait Pos:Named { nm():Int -> 1 }
                1""");
        assertTrue(err.contains("sort alias") && err.contains("refined"),
                () -> "expected a refined-alias assign-trait rejection; got: " + err);
    }

    // ===== Group C — documented currently-unsupported combinations ==========

    /**
     * Refining ON TOP of an alias base ({@code [Coord:@>0]} where
     * {@code Coord=Int}) is not supported: AliasResolver does not unwrap an
     * alias in a refinement's base position (a known gap). The message names the
     * base and the workaround (refine over the primitive directly). If this
     * starts working, tighten it to a value assertion.
     */
    @Test void refineOnAliasBase_stillRejected_butClearly() {
        String err = reject("""
                let Coord:Type[Int]
                function f(n:[Coord:@>0]):Int -> n * 2
                f(5)""");
        assertTrue(err.contains("Coord") && err.toLowerCase().contains("refinement"),
                () -> "expected a refinement-base diagnostic naming Coord; got: " + err);
    }

    /**
     * An alias FOR A TRAIT used as a parameter sort ({@code let N:Type[Num]};
     * {@code f(x:N)}) resolves the trait's method contract at the call site so
     * {@code x.val()} dispatches. (Previously failed with "No method 'val' on
     * type 'N'" because method resolution ran before alias resolution in the
     * single-file path; the passes are now ordered alias-first, matching
     * IrCompiler's canonical order.)
     */
    @Test void aliasOfTraitAsParamBound_resolvesTraitContract() {
        assertEquals("8", run("""
                trait Num{ val:[Method():Int] }
                let N:Type[Num]
                function f(x:N):Int -> x.val()
                struct T(v:Int)
                assign trait T:Num { val():Int -> this.v }
                f(T(8))"""));
    }
}
