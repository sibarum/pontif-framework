package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Free type parameters (docs/type-parameters.md), slice 3a — the `[type T]` slot
 * on a TRAIT (`let Expr[type T]:Type{…}`): declare it after the name, scope it
 * over the trait's member sorts. This is the type *parameter* (chosen from
 * outside, §2.1), distinct from an associated type (fixed by the implementor).
 * Declaration-level; the trait-application sort `[Expr[Int]]` and parametric
 * impls are slice 3b.
 */
class TypeParameterTraitTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private void compiles(String src) {
        PontifCompiler.CompileResult r = compiler.compileAlt(src, "tpt.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
    }

    private PontifCompiler.CompileResult.Failed rejects(String src) {
        return assertInstanceOf(PontifCompiler.CompileResult.Failed.class,
                compiler.compileAlt(src, "tpt.ptf"), "expected a compile rejection");
    }

    @Test
    void parametricTrait_withTypeParamInMembers_declares() {
        compiles("""
                let Producer[type T]:Type{ make:[Method():T] }
                0
                """);
    }

    @Test
    void parametricTrait_multipleParams_inAnyPosition() {
        compiles("""
                let Mapper[type A, type B]:Type{ apply:[Method(A):B] }
                0
                """);
    }

    @Test
    void parametricTrait_typeParam_doesNotLeak() {
        // `T` is scoped to the trait's members; elsewhere it is unknown.
        PontifCompiler.CompileResult.Failed f = rejects("""
                let Producer[type T]:Type{ make:[Method():T] }
                function f(x:T):Int -> 1
                f(5)
                """);
        assertTrue(f.error().text().contains("Unknown sort 'T'"), () -> f.error().text());
    }
}
