package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Free type parameters (docs/type-parameters.md), slice 2c — inline
 * destructuring (§2.4). A bare unknown name as a type ARGUMENT in a param sort
 * (`b:Box[T]`) introduces a type variable without a `[type T]` slot, scoped over
 * the rest of the signature — so a combinator can project an argument's element
 * type into a name. A top-level param name (`y:E`) is NOT a destructure: an
 * unknown sort there stays a typo.
 */
class TypeParameterDestructureTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "tpd.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        return runner.run(r, PontifRunner.Engine.INTERPRETER).text();
    }

    private PontifCompiler.CompileResult.Failed rejects(String src) {
        return assertInstanceOf(PontifCompiler.CompileResult.Failed.class,
                compiler.compile(src, "tpd.ptf"), "expected a compile rejection");
    }

    @Test
    void inlineDestructure_bindsTypeVar_andRuns() {
        // `T` is introduced by `Box[T]` in the param sort, no slot, and used as
        // the return — scopes and runs.
        assertEquals("9", run("""
                struct Box[type T](value:T)
                function unbox(b:Box[T]):T -> b.value
                unbox(Box(9))
                """));
    }

    @Test
    void inlineDestructure_isNestedAware() {
        // The destructured name is found inside the bracket regardless of depth.
        assertEquals("3", run("""
                struct Box[type T](value:T)
                struct Wrap[type T](inner:T)
                function deep(w:Wrap[Box[T]]):Box[T] -> w.inner
                deep(Wrap(Box(3))).value
                """));
    }

    @Test
    void topLevelParamName_isNotDestructured() {
        // `E` at the top level of a param sort is not inside any parametric
        // bracket, so it is NOT collected — it stays an unknown sort.
        PontifCompiler.CompileResult.Failed f = rejects("""
                function g(y:E):Int -> 1
                g(5)
                """);
        assertTrue(f.error().text().contains("Unknown sort 'E'"), () -> f.error().text());
    }
}
