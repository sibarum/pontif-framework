package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Free type parameters (docs/type-parameters.md), slice 1b-ii — construction-time
 * derivation. Constructing a parametric struct recovers its `type T` from the
 * field-value arguments ("the field is the witness", §3.3); arguments that bind a
 * parameter two incompatible ways are rejected (§3.1, the unify/extract step).
 */
class TypeParameterConstructionTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        PontifCompiler.CompileResult r = compiler.compileAlt(src, "tpc.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        return runner.run(r, PontifRunner.Engine.INTERPRETER).text();
    }

    private PontifCompiler.CompileResult.Failed rejects(String src) {
        return assertInstanceOf(PontifCompiler.CompileResult.Failed.class,
                compiler.compileAlt(src, "tpc.ptf"), "expected a compile rejection");
    }

    @Test
    void parametricStruct_constructsAndAccessesField() {
        assertEquals("5", run("""
                struct Box[type T](value:T)
                Box(5).value
                """));
    }

    @Test
    void consistentTypeParam_acrossFields_compiles() {
        // a and b both bind T = Int — consistent, so it constructs.
        assertEquals("1", run("""
                struct Pair[type T](a:T, b:T)
                Pair(1, 2).a
                """));
    }

    @Test
    void conflictingTypeParam_isRejected() {
        // a binds T = Int, b binds T = Bool — the arguments disagree on T.
        PontifCompiler.CompileResult.Failed f = rejects("""
                struct Pair[type T](a:T, b:T)
                Pair(1, true)
                """);
        assertTrue(f.error().text().contains("Type parameter 'T'")
                        && f.error().text().contains("disagree"),
                () -> f.error().text());
    }

    // --- parametric let assignment routes through Assignability (roadmap §4.5 item 2) ---

    @Test
    void parametricLet_matchingTypeArg_compilesAndRuns() {
        // A declared parametric let sort is now decided by the type-arg-aware engine; a matching
        // instantiation (b:Box[Int] into x:Box[Int]) is EXACT and binds. (The value carries the
        // applied sort via the param; a direct `let b:Box[Int] = Box(5)` is a separate case — see
        // parametricLet_directConstruction below — because construction inference yields a bare Box.)
        assertEquals("5", run("""
                struct Box[type T](value:T)
                function unwrap(b:Box[Int]):Int -> ( let x:Box[Int] = b
                 x.value )
                unwrap(Box(5))
                """));
    }

    @Test
    void parametricLet_mismatchedTypeArg_isRejected() {
        // Invariance: a Box[Bool]-typed value cannot bind a Box[Int] let. Before item 2 the engine
        // was type-arg-blind and this slipped through; now the parser rejects it via Assignability.
        PontifCompiler.CompileResult.Failed f = rejects("""
                struct Box[type T](value:T)
                function first(b:Box[Bool]):Int -> ( let x:Box[Int] = b
                 0 )
                first(Box(true))
                """);
        assertTrue(f.error().text().toLowerCase().contains("different types"),
                () -> f.error().text());
    }

    @Test
    void parametricLet_directConstruction_carriesDerivedTypeArg() {
        // Construction inference now stamps the derived type-arg (Box(5) → Box[Int]), so a direct
        // `let b:Box[Int] = Box(5)` proves EXACT and runs (roadmap §4.5 item 2 follow-up — this was a
        // known false-rejection while inference yielded a bare Box).
        assertEquals("5", run("""
                struct Box[type T](value:T)
                let b:Box[Int] = Box(5)
                b.value
                """));
    }

    @Test
    void parametricLet_directConstruction_wrongTypeArg_isRejected() {
        // The derived type-arg is now checked: Box(true) is Box[Bool], not the declared Box[Int].
        PontifCompiler.CompileResult.Failed f = rejects("""
                struct Box[type T](value:T)
                let b:Box[Int] = Box(true)
                b.value
                """);
        assertTrue(f.error().text().toLowerCase().contains("different types"),
                () -> f.error().text());
    }
}
