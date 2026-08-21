package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice 2 of associated types (docs/associated-types.md): a trait may declare a
 * type-level member with the `type X` declarator and reference it in its own
 * member sorts (any position). This is declaration-level only — binding the
 * associated type in an impl is slice 3. Validates that `type T` is in scope for
 * the trait's signatures, and that an undeclared reference is still rejected.
 */
class AssociatedTypeDeclTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private void compiles(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "assoc.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
    }

    private PontifCompiler.CompileResult.Failed rejects(String src) {
        return assertInstanceOf(PontifCompiler.CompileResult.Failed.class,
                compiler.compile(src, "assoc.ptf"), "expected a compile rejection");
    }

    @Test
    void associatedType_inReturnPosition_isInScope() {
        compiles("""
                trait Producer{
                  type T,
                  get:[Method():T]
                }
                0
                """);
    }

    @Test
    void associatedType_inArgumentAndNestedPositions_isInScope() {
        compiles("""
                trait Sink{
                  type T,
                  put:[Method(T):Sink],
                  swap:[Method(T):T]
                }
                0
                """);
    }

    @Test
    void undeclaredTypeInTraitSignature_isRejected() {
        // `Undeclared` is neither a primitive, a declared type, nor an
        // associated type of this trait — still an Unknown sort.
        PontifCompiler.CompileResult.Failed f = rejects("""
                trait Producer{
                  type T,
                  get:[Method():Undeclared]
                }
                0
                """);
        assertTrue(f.error().text().contains("Unknown sort 'Undeclared'"),
                () -> f.error().text());
    }

    @Test
    void boundedAssociatedType_withDeclaredBound_compiles() {
        // `type T:Showable` — the bound names a declared trait. (Checking that an
        // impl's binding actually satisfies the bound is slice 4; this is just
        // that the bounded declaration parses and validates.)
        compiles("""
                trait Showable{ describe:[Method():Int] }
                trait Box{
                  type T:Showable,
                  get:[Method():T]
                }
                0
                """);
    }

    @Test
    void associatedTypeName_doesNotLeakOutsideTheTrait() {
        // `T` is scoped to Producer; using it as a sort elsewhere is unknown.
        PontifCompiler.CompileResult.Failed f = rejects("""
                trait Producer{
                  type T,
                  get:[Method():T]
                }
                function f(x:T):Int -> 1
                f(5)
                """);
        assertTrue(f.error().text().contains("Unknown sort 'T'"), () -> f.error().text());
    }
}
