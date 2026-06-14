package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Free type parameters (docs/type-parameters.md), slice 1a — the `[type T]`
 * declaration on a struct: parse the bracket slot after the name, scope the
 * parameter over the field sorts, and validate. Declaration-level only —
 * `Name[Arg]` application and construction-time derivation are later sub-slices.
 */
class TypeParameterDeclTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private void compiles(String src) {
        PontifCompiler.CompileResult r = compiler.compileAlt(src, "tparam.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
    }

    private PontifCompiler.CompileResult.Failed rejects(String src) {
        return assertInstanceOf(PontifCompiler.CompileResult.Failed.class,
                compiler.compileAlt(src, "tparam.ptf"), "expected a compile rejection");
    }

    @Test
    void structTypeParam_inFieldPosition_isInScope() {
        compiles("""
                struct Box[type T](value:T)
                0
                """);
    }

    @Test
    void multipleTypeParams_areInScope() {
        compiles("""
                struct Pair[type A, type B](first:A, second:B)
                0
                """);
    }

    @Test
    void typeParam_usableInMultipleFieldPositions() {
        compiles("""
                struct Two[type T](first:T, second:T)
                0
                """);
    }

    @Test
    void typeParam_doesNotLeakOutsideTheStruct() {
        // `T` is scoped to Box; using it as a sort elsewhere is unknown.
        PontifCompiler.CompileResult.Failed f = rejects("""
                struct Box[type T](value:T)
                function f(x:T):Int -> 1
                f(5)
                """);
        assertTrue(f.error().text().contains("Unknown sort 'T'"), () -> f.error().text());
    }
}
