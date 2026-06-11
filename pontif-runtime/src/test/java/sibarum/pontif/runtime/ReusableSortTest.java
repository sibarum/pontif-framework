package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reusable sorts: {@code let NAME:Type[sortExpr]} binds a named, reusable sort
 * alias (refinements, unions of refined bases, named sorts) usable wherever a
 * sort annotation goes. Lowers to an {@code IrStmt.TypeAlias} the AliasResolver
 * inlines — the bracketed sibling of the {@code Type{...}} trait form.
 */
class ReusableSortTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private void assertCompiles(String src) {
        CompileResult r = compiler.compileAlt(src, "reusablesort.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "expected compile success; got: "
                        + ((CompileResult.Failed) r).error().text());
    }

    private String assertRejected(String src) {
        CompileResult r = compiler.compileAlt(src, "reusablesort.ptf");
        assertInstanceOf(CompileResult.Failed.class, r, "expected compile failure");
        return ((CompileResult.Failed) r).error().text();
    }

    @Test
    void unionAlias_memberValueCompiles() {
        // 1 is a nonzero Int → fits the [Int:@!=0] branch of the alias.
        assertCompiles("""
                let AnyNumberNotZero:Type[[Int:@!=0]|[Decimal:@!=0]]
                let x:AnyNumberNotZero = 1
                x
                """);
    }

    @Test
    void sameBaseUnionAlias_provablyDisjointValueRejected() {
        // A same-base union normalizes to one Refined ([Int:@<0 | @>10]), so a value
        // disjoint from BOTH bands (5) is provably false → compile error. (A
        // cross-base union's non-member instead defers to a runtime check, since the
        // kernel can't decide membership across the Int/Decimal boundary statically.)
        String err = assertRejected("""
                let Banded:Type[[Int:@<0]|[Int:@>10]]
                let x:Banded = 5
                x
                """);
        assertTrue(err.contains("x") || err.toLowerCase().contains("disjoint")
                        || err.toLowerCase().contains("satisfy") || err.contains("@"),
                () -> "expected the disjoint value to be rejected; got: " + err);
    }

    @Test
    void refinementAlias_reusedAsParamAndReturn() {
        // A refinement alias used in two annotation positions: inc(n>0) returns >0.
        assertCompiles("""
                let Positive:Type[[Int:@>0]]
                function inc(n:Positive):Positive -> n + 1
                inc(5)
                """);
    }

    @Test
    void typeAlias_cannotHaveValue() {
        // Type[...] declares a sort, not a value.
        String err = assertRejected("""
                let Positive:Type[[Int:@>0]] = 5
                42
                """);
        assertTrue(err.toLowerCase().contains("alias") || err.toLowerCase().contains("value")
                        || err.toLowerCase().contains("parse"),
                () -> "expected a Type[...]-with-value rejection; got: " + err);
    }
}
