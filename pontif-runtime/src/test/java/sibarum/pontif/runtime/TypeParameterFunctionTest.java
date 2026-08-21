package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Free type parameters (docs/type-parameters.md), slice 2a — the `[type E]` slot
 * on a function: declare it after the name, scope it over the param and return
 * sorts, run. The call-site unify that *derives* `E` from the arguments is slice
 * 2b; here `E` flows structurally (the body just moves values), which already
 * runs.
 */
class TypeParameterFunctionTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "tpf.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        return runner.run(r, PontifRunner.Engine.INTERPRETER).text();
    }

    private PontifCompiler.CompileResult.Failed rejects(String src) {
        return assertInstanceOf(PontifCompiler.CompileResult.Failed.class,
                compiler.compile(src, "tpf.ptf"), "expected a compile rejection");
    }

    @Test
    void functionTypeParam_inParamAndReturn_compilesAndRuns() {
        assertEquals("5", run("""
                function id[type E](x:E):E -> x
                id(5)
                """));
    }

    @Test
    void functionTypeParam_overParametricStruct_runs() {
        // E is used inside a parametric application in the param sort.
        assertEquals("7", run("""
                struct Box[type T](value:T)
                function unbox[type E](b:Box[E]):E -> b.value
                unbox(Box(7))
                """));
    }

    @Test
    void functionTypeParam_doesNotLeakToAnotherFunction() {
        // `E` is scoped to id; a second function without its own `[type E]`
        // cannot reference it.
        PontifCompiler.CompileResult.Failed f = rejects("""
                function id[type E](x:E):E -> x
                function g(y:E):Int -> 1
                g(5)
                """);
        assertTrue(f.error().text().contains("Unknown sort 'E'"), () -> f.error().text());
    }
}
