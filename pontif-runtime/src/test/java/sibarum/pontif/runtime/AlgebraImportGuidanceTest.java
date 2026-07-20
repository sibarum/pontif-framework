package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A proof marker ({@code assign proof f:Algebraic}) names a TYPE that must be in scope, the
 * same as any other type reference. {@code Algebraic} is recognized globally (NameResolver
 * keeps it spellable bare so the metareference sort stamp / runtime value / trait all agree),
 * but that recognition does NOT waive the import: {@code assign proof f:Algebraic} without
 * {@code requires pontif.algebra} is rejected at the claim with the ordinary unknown-sort
 * error — rather than compiling and then failing far away when {@code .ast}/{@code .eval} can't
 * find their members. General, not keyed on {@code Algebraic} (see SortChecker#validateProofMarkers).
 */
class AlgebraImportGuidanceTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private String failureText(String src) {
        var r = compiler.compileAlt(src, "<editor>");
        assertInstanceOf(PontifCompiler.CompileResult.Failed.class, r,
                "expected a compile failure, got " + r);
        return r.toString();
    }

    @Test
    void algebraicProofWithoutImport_isRejectedAtTheClaim() {
        String msg = failureText("""
                function poly(x:Decimal):Decimal -> x*x + 2.0*x + 1.0
                assign proof poly:Algebraic
                0
                """);
        // The ordinary unknown-sort diagnostic, pointing at the missing import.
        assertTrue(msg.contains("Unknown sort 'Algebraic'") && msg.contains("import"),
                () -> "should be the standard unknown-sort error naming Algebraic: " + msg);
    }

    @Test
    void algebraicProofWithImport_stillCompiles() {
        var r = compiler.compileAlt("""
                requires pontif.algebra.{Algebraic}
                function poly(x:Decimal):Decimal -> x*x + 2.0*x + 1.0
                assign proof poly:Algebraic
                $poly[Decimal].eval(3.0)
                """, "<editor>");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                "importing pontif.algebra should make the algebraic proof + .eval compile, got " + r);
    }
}
