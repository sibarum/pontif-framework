package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Free type parameters (docs/type-parameters.md), slice 3b — the
 * trait-application sort ({@code [Stream[Int]]}) and parametric impls
 * ({@code assign trait Element[type T]:Stream[T]}, the struct's parameter
 * flowing into the trait's).
 *
 * <p>The trait-application sort is not a {@code validateSortNames} concern:
 * AliasResolver <em>inlines</em> a trait reference, so {@code Stream[Int]}
 * inlines the trait body with its parameter substituted ({@code E↦Int}) and an
 * arity check. A parametric impl introduces its own {@code [type T]} binder —
 * the toggle that tells a forwarded variable {@code Stream[T]} apart from a
 * concrete {@code Stream[SomeType]} — which {@code validateTraitImpl} scopes,
 * then zips the trait's declared {@code [type E]} against the supplied args and
 * substitutes into the contract before matching.
 */
class TypeParameterTraitSortTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private void compiles(String src) {
        PontifCompiler.CompileResult r = compiler.compileAlt(src, "tpts.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
    }

    private PontifCompiler.CompileResult.Failed rejects(String src) {
        return assertInstanceOf(PontifCompiler.CompileResult.Failed.class,
                compiler.compileAlt(src, "tpts.ptf"), "expected a compile rejection");
    }

    // --- The trait-application sort `[Stream[Int]]` ---

    @Test
    void parametricTraitSort_appliedToConcrete_inlines() {
        // `Stream[Int]` as a function-param sort: the trait body inlines with
        // E↦Int substituted. Compiles (E is supplied, no leftover variable).
        compiles("""
                let Stream[type E]:Type{ head:[Method():E] }
                function f(s:Stream[Int]):Int -> 1
                0
                """);
    }

    @Test
    void parametricTraitSort_wrongArity_isRejected() {
        // One declared `[type E]`, two args supplied at the application site.
        PontifCompiler.CompileResult.Failed f = rejects("""
                let Stream[type E]:Type{ head:[Method():E] }
                function f(s:Stream[Int, Bool]):Int -> 1
                0
                """);
        assertTrue(f.error().text().contains("expects 1 type argument")
                        && f.error().text().contains("got 2"),
                () -> f.error().text());
    }

    // --- Parametric impls: the struct's parameter flowing into the trait's ---

    @Test
    void parametricImpl_forwardsTheStructParameter() {
        // Box's own `T` flows into Producer[T]; the contract's `make:[Method():E]`
        // (E↦T) matches the impl's `make():T`.
        compiles("""
                struct Box[type T](value:T)
                let Producer[type E]:Type{ make:[Method():E] }
                assign trait Box[type T]:Producer[T] {
                    make():T -> this.value
                }
                0
                """);
    }

    @Test
    void concreteImpl_suppliesAConcreteArg() {
        // A non-parametric struct pins the trait arg to a concrete type; no
        // binder needed (E↦Int).
        compiles("""
                struct IntBox(value:Int)
                let Producer[type E]:Type{ make:[Method():E] }
                assign trait IntBox:Producer[Int] {
                    make():Int -> this.value
                }
                0
                """);
    }

    @Test
    void impl_returnDisagreesWithTraitArg_isRejected() {
        // The trait arg `Bool` substitutes E↦Bool, so the contract requires
        // `make():Bool`; the impl returns Int — a mismatch. Observable proof
        // that the supplied arg drives the contract.
        PontifCompiler.CompileResult.Failed f = rejects("""
                struct IntBox(value:Int)
                let Producer[type E]:Type{ make:[Method():E] }
                assign trait IntBox:Producer[Bool] {
                    make():Int -> this.value
                }
                0
                """);
        assertTrue(f.error().text().contains("Bool"), () -> f.error().text());
    }

    @Test
    void impl_wrongTraitArgArity_isRejected() {
        PontifCompiler.CompileResult.Failed f = rejects("""
                struct IntBox(value:Int)
                let Producer[type E]:Type{ make:[Method():E] }
                assign trait IntBox:Producer[Int, Bool] {
                    make():Int -> this.value
                }
                0
                """);
        assertTrue(f.error().text().contains("supplies 2 type argument")
                        && f.error().text().contains("declares 1"),
                () -> f.error().text());
    }

    @Test
    void impl_bindsWrongParameterCount_isRejected() {
        // Box declares one `[type T]`; the impl claims two — a lie about Box's
        // arity.
        PontifCompiler.CompileResult.Failed f = rejects("""
                struct Box[type T](value:T)
                let Producer[type E]:Type{ make:[Method():E] }
                assign trait Box[type A, type B]:Producer[A] {
                    make():A -> this.value
                }
                0
                """);
        assertTrue(f.error().text().contains("binds 2 type parameter")
                        && f.error().text().contains("declares 1"),
                () -> f.error().text());
    }

    @Test
    void concreteImpl_satisfiesParametricDataAttributeWithField() {
        // The trait's data attribute `value:T` substitutes E↦Int, so the field
        // `value:Int` on IntLit satisfies it — an empty impl body.
        compiles("""
                let Literal[type T]:Type{
                  value:T
                }
                struct IntLit(value:Int)
                assign trait IntLit:Literal[Int]{
                }
                0
                """);
    }

    @Test
    void impl_withoutBinder_treatsForwardedNameAsUnknown() {
        // No `[type T]` binder, so `T` in `Producer[T]` is NOT a variable — it
        // is read as a concrete type name, which is unknown. This is the binder
        // acting as the literal-vs-variable toggle (docs/type-parameters.md §2.1).
        PontifCompiler.CompileResult.Failed f = rejects("""
                struct Box[type T](value:T)
                let Producer[type E]:Type{ make:[Method():E] }
                assign trait Box:Producer[T] {
                    make():T -> this.value
                }
                0
                """);
        assertTrue(f.error().text().contains("Unknown sort 'T'"), () -> f.error().text());
    }
}
