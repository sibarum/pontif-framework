package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Free type parameters (docs/type-parameters.md), the is-a-base form — a struct
 * extends a PARAMETRIC base in its {@code :[…]} slot
 * ({@code struct IntLit:[Literal[Int]](value:Int)}). The type argument is
 * <b>invariant</b>: substituting it into the base struct's fields, the child's
 * field providing each base field must be EXACTLY that sort — a refinement of it
 * (or a different base) is a falsehood (RULED 2026-06-14: "the sorts should match
 * exactly").
 */
class TypeParameterIsaBaseTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private void compiles(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "isa.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
    }

    private PontifCompiler.CompileResult.Failed rejects(String src) {
        return assertInstanceOf(PontifCompiler.CompileResult.Failed.class,
                compiler.compile(src, "isa.ptf"), "expected a compile rejection");
    }

    @Test
    void bareParametricBase_matchingField_compiles() {
        compiles("""
                struct Literal[type T](value:T)
                struct IntLit:[Literal[Int]](value:Int)
                0
                """);
    }

    @Test
    void morphismParametricBase_matchingField_compiles() {
        compiles("""
                struct Literal[type T](value:T)
                struct IntLit:[Literal[Int]:@.value==value](value:Int)
                0
                """);
    }

    @Test
    void parametricBase_wrongTypeArg_isRejected() {
        // Claims is-a Literal[Bool] while holding value:Int.
        PontifCompiler.CompileResult.Failed f = rejects("""
                struct Literal[type T](value:T)
                struct BadLit:[Literal[Bool]](value:Int)
                0
                """);
        assertTrue(f.error().text().contains("invariant")
                        || f.error().text().contains("not exactly"),
                () -> f.error().text());
    }

    @Test
    void parametricBase_wrongTypeArg_morphism_isRejected() {
        PontifCompiler.CompileResult.Failed f = rejects("""
                struct Literal[type T](value:T)
                struct BadLit:[Literal[Bool]:@.value==value](value:Int)
                0
                """);
        assertTrue(f.error().text().contains("not exactly") || f.error().text().contains("invariant"),
                () -> f.error().text());
    }

    @Test
    void parametricBase_refinementInsteadOfExact_isRejected() {
        // T resolves to Int, so the field can't be [Int:@>0] — a refinement is a
        // different sort, not an acceptable substitute (the ruling's example).
        PontifCompiler.CompileResult.Failed f = rejects("""
                struct Literal[type T](value:T)
                struct PosLit:[Literal[Int]](value:[Int:@>0])
                0
                """);
        assertTrue(f.error().text().contains("not exactly") || f.error().text().contains("invariant"),
                () -> f.error().text());
    }

    @Test
    void forwardingParametricBase_compiles() {
        // The child forwards its own T into the base; T == T exactly.
        compiles("""
                struct Box[type T](value:T)
                struct Wrap[type T]:[Box[T]](value:T)
                0
                """);
    }

    @Test
    void parametricBase_wrongArity_isRejected() {
        PontifCompiler.CompileResult.Failed f = rejects("""
                struct Literal[type T](value:T)
                struct X:[Literal[Int, Bool]](value:Int)
                0
                """);
        assertTrue(f.error().text().contains("type argument") && f.error().text().contains("declares 1"),
                () -> f.error().text());
    }
}
