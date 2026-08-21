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
        PontifCompiler.CompileResult r = compiler.compile(src, "tparam.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
    }

    private PontifCompiler.CompileResult.Failed rejects(String src) {
        return assertInstanceOf(PontifCompiler.CompileResult.Failed.class,
                compiler.compile(src, "tparam.ptf"), "expected a compile rejection");
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

    // --- `Name[Arg]` parametric application (slice 1b-i) ---

    @Test
    void parametricApplication_inValidatedPosition_resolves() {
        // Box[Int] as a function-param sort: head Box known, arg Int known.
        compiles("""
                struct Box[type T](value:T)
                function f(b:Box[Int]):Int -> 1
                0
                """);
    }

    @Test
    void recursiveParametricStruct_declares() {
        // The self-application `Node[T]` inside the struct's own field parses and
        // stays nominal (no unrolling), like any recursive struct.
        compiles("""
                struct Node[type T](value:T, next:Node[T])
                0
                """);
    }

    @Test
    void unknownTypeArg_inReturnPosition_isRejected() {
        // Type arguments are validated where inline destructuring does NOT apply.
        // A return sort is not a destructure site (§2.4 — destructuring projects
        // from an argument), so an unknown arg there is still an unknown sort.
        // (In a *param* sort, `Box[Bad]` would instead bind `Bad` as a
        // destructured type variable — see TypeParameterDestructureTest.)
        PontifCompiler.CompileResult.Failed f = rejects("""
                struct Box[type T](value:T)
                function f(x:Int):Box[Bad] -> x
                f(0)
                """);
        assertTrue(f.error().text().contains("Unknown sort 'Bad'"), () -> f.error().text());
    }
}
