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
}
