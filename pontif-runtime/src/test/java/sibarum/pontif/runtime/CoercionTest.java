package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * User-defined coercions: {@code cast Target:(name:Source) -> body}, the definition
 * the cast invocation {@code (Target:value)} resolves to (docs/dispatch-unification.md
 * §Coercion, docs/cross-module-dispatch.md). Pontif's answer to Julia-style implicit
 * promotion — explicit, named, resolved on the one shared dispatch engine (so multiple
 * sources to one target and refined sources come free), under the rules: no
 * primitive↔primitive, the orphan rule (declaring module owns source or target), and
 * at most one coercion per (source, target) pair.
 */
class CoercionTest {

    @TempDir
    Path dir;

    private String run(String src) throws Exception {
        var r = new PontifCompiler().compileAlt(src, "t.ptf", dir);
        var run = new PontifRunner().run(r, Engine.INTERPRETER);
        assertFalse(run.isError(), () -> "expected success; got: " + run.text());
        return run.text();
    }

    private String compileError(String src) throws Exception {
        var r = new PontifCompiler().compileAlt(src, "t.ptf", dir);
        CompileResult.Failed f = assertInstanceOf(CompileResult.Failed.class, r,
                () -> "expected a compile error");
        return f.error().text();
    }

    @Test
    void structToStruct_resolvesViaTheCast() throws Exception {
        assertEquals("300", run("""
                struct Celsius(deg:Int)
                struct Kelvin(deg:Int)
                cast Kelvin:(c:Celsius) -> Kelvin(c.deg + 273)
                (Kelvin: Celsius(27)).deg
                """));
    }

    @Test
    void multipleSourcesToOneTarget_dispatchByValueType() throws Exception {
        // Two coercions to Kelvin from distinct sources — distinct (source,target)
        // pairs, both legal. The shared engine selects by the value's runtime type.
        String prog = """
                struct Celsius(deg:Int)
                struct Rankine(deg:Int)
                struct Kelvin(deg:Int)
                cast Kelvin:(c:Celsius) -> Kelvin(c.deg + 273)
                cast Kelvin:(r:Rankine) -> Kelvin(r.deg + 1)
                """;
        assertEquals("273", run(prog + "(Kelvin: Celsius(0)).deg\n"));
        assertEquals("1", run(prog + "(Kelvin: Rankine(0)).deg\n"));
    }

    @Test
    void refinedSource_gatesByTheSourceRefinement() throws Exception {
        // The coercion only applies to a positive Int source; a positive value
        // resolves, exercising the runtime source-refinement check.
        assertEquals("7", run("""
                struct Tagged(v:Int)
                cast Tagged:(n:[Int:@>0]) -> Tagged(n)
                (Tagged: 7).v
                """));
    }

    @Test
    void refinedTarget_keysOnTheTargetBase() throws Exception {
        // A refined target [Int:@>0] keys the coercion under its base (Int); the
        // body produces the value, the cast types to the refinement.
        assertEquals("5", run("""
                struct Pos(v:Int)
                cast [Int:@>0]:(p:Pos) -> p.v
                ([Int:@>0]: Pos(5))
                """));
    }

    @Test
    void primitiveToPrimitive_isRejected() throws Exception {
        String err = compileError("""
                cast Decimal:(n:Int) -> Decimal(n, 0)
                0
                """);
        assertTrue(err.contains("both are primitive") || err.contains("primitive tower"),
                () -> err);
    }

    @Test
    void duplicateSourceTargetPair_isRejected() throws Exception {
        String err = compileError("""
                struct Celsius(deg:Int)
                struct Kelvin(deg:Int)
                cast Kelvin:(c:Celsius) -> Kelvin(c.deg + 273)
                cast Kelvin:(c:Celsius) -> Kelvin(c.deg + 274)
                0
                """);
        assertTrue(err.contains("duplicate coercion"), () -> err);
    }

    @Test
    void crossModule_coercionOwnedBySourceModule_resolves() throws Exception {
        // Module A owns Celsius and declares the coercion to Kelvin (also A's) —
        // orphan satisfied (owns both). B imports the types and uses the cast.
        Files.writeString(dir.resolve("temp.ptf"), """
                module sci.temp
                exports @.{Celsius, Kelvin}
                struct Celsius(deg:Int)
                struct Kelvin(deg:Int)
                cast Kelvin:(c:Celsius) -> Kelvin(c.deg + 273)
                """);
        Files.writeString(dir.resolve("app.ptf"), """
                module app.main
                requires sci.temp.{Celsius, Kelvin}
                (Kelvin: Celsius(100)).deg
                """);
        var r = new PontifCompiler().compileAlt(
                Files.readString(dir.resolve("app.ptf")), "app.ptf", dir);
        var run = new PontifRunner().run(r, Engine.INTERPRETER);
        assertFalse(run.isError(), () -> "expected success; got: " + run.text());
        assertEquals("373", run.text());
    }

    @Test
    void orphanCoercion_owningNeitherType_isRejected() throws Exception {
        // Module owns neither source nor target → orphan rule rejects the coercion.
        Files.writeString(dir.resolve("a.ptf"), """
                module lib.a
                exports @.{A}
                struct A(v:Int)
                """);
        Files.writeString(dir.resolve("b.ptf"), """
                module lib.b
                exports @.{B}
                struct B(v:Int)
                """);
        Files.writeString(dir.resolve("c.ptf"), """
                module app.c
                requires lib.a.{A}
                requires lib.b.{B}
                cast B:(a:A) -> B(a.v)
                (B: A(1)).v
                """);
        var r = new PontifCompiler().compileAlt(
                Files.readString(dir.resolve("c.ptf")), "c.ptf", dir);
        CompileResult.Failed f = assertInstanceOf(CompileResult.Failed.class, r,
                () -> "expected an orphan-coercion compile error");
        assertTrue(f.error().text().contains("orphan coercion"), () -> f.error().text());
    }

    @Test
    void parser_twoBinders_isRejected() throws Exception {
        String err = compileError("""
                struct Celsius(deg:Int)
                struct Kelvin(deg:Int)
                cast Kelvin:(c:Celsius, d:Celsius) -> Kelvin(c.deg)
                0
                """);
        assertTrue(err.contains("exactly one source binder"), () -> err);
    }

    // --- the cast gate (C3 §4.5 item 3): a cast with no path is a compile error, not a runtime throw ---

    @Test
    void castWithNoCoercion_isCompileError() throws Exception {
        // No `cast Int:(String)` is declared and Int isn't a String render — so `(Int:"abc")` has no
        // runtime-executable path. The gate rejects it at compile time (§1d) instead of the runtime
        // "No coercion" throw.
        String err = compileError("(Int:\"abc\")");
        assertTrue(err.contains("Cannot cast") && err.contains("no such cast"), () -> err);
    }

    @Test
    void castOfUnrenderableToString_isCompileError() throws Exception {
        // A struct isn't renderable to String and no `cast String:(Point)` applies (String targets
        // render, never dispatch a coercion) — a compile error, not a runtime "cannot render" throw.
        String err = compileError("""
                struct Point(x:Int, y:Int)
                (String:Point(1, 2))
                """);
        assertTrue(err.contains("Cannot cast"), () -> err);
    }

    @Test
    void renderableCast_isAllowed() throws Exception {
        // The gate must NOT reject a legal String render (Int is renderable).
        assertEquals("\"12\"", run("(String:12)"));
    }
}
