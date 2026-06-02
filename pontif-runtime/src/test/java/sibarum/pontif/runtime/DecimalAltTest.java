package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.parser.AltParser;
import sibarum.pontif.parser.ParseException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for the {@code Decimal} value type (BigDecimal-backed):
 * literals, {@code + - *} arithmetic, comparisons, equality up-to-scale, and
 * dispatch over {@code Decimal}-typed params. Refinements over {@code Decimal}
 * are rejected for now (the discharge engine is integer-only). Division is a
 * separate, cross-type slice and is intentionally absent.
 */
class DecimalAltTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = AltParser.parseModule(src, "t.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    private void assertDecimal(String expected, Object actual) {
        assertTrue(actual instanceof BigDecimal,
                () -> "Expected a BigDecimal, got " + (actual == null ? "null" : actual.getClass().getSimpleName()));
        assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual),
                () -> "Expected " + expected + " but got " + actual);
    }

    @Test
    void decimalLiteral_evaluatesToBigDecimal() throws Exception {
        assertDecimal("3.14", run("3.14"));
    }

    @Test
    void negativeDecimalLiteral_evaluates() throws Exception {
        assertDecimal("-2.5", run("-2.5"));
    }

    @Test
    void decimalArithmetic_addSubMul_isExact() throws Exception {
        assertDecimal("4.0", run("1.5 + 2.5"));
        assertDecimal("3.5", run("5.0 - 1.5"));
        assertDecimal("6.0", run("2.0 * 3.0"));
        // BigDecimal +−× are exact — no binary-float surprise.
        assertDecimal("0.3", run("0.1 + 0.2"));
    }

    @Test
    void decimalComparison_yieldsBool() throws Exception {
        assertEquals(true, run("1.5 < 2.5"));
        assertEquals(false, run("2.5 < 1.5"));
        assertEquals(true, run("2.5 >= 2.5"));
    }

    @Test
    void decimalEquality_isUpToScale_notBitwise() throws Exception {
        // compareTo semantics: 2.0 and 2.00 are equal despite differing scale
        // (BigDecimal.equals would say false — the footgun we avoid).
        assertEquals(true, run("2.0 == 2.00"));
        assertEquals(false, run("2.0 != 2.00"));
        assertEquals(true, run("1.5 != 1.6"));
    }

    @Test
    void decimalThroughFunction_dispatchesAndComputes() throws Exception {
        // Exercises toSymExpr(BigDecimal) -> SymExpr.Dec and dispatch over a
        // bare Decimal param.
        String src = """
                function add(a:Decimal, b:Decimal):Decimal -> a + b
                add(1.5, 2.5)
                """;
        assertDecimal("4.0", run(src));
    }

    @Test
    void decimalParam_andDecimalLet_compose() throws Exception {
        String src = """
                function scale2(x:Decimal):Decimal -> x * 2.0
                let half = 1.25
                scale2(half)
                """;
        assertDecimal("2.50", run(src));
    }

    @Test
    void decimalRefinement_isRejectedWithClearError() {
        CompileException ex = assertThrows(CompileException.class, () ->
                run("function f(x:[Decimal:@>0]):Decimal -> x\n0"));
        assertTrue(ex.getMessage().contains("Decimal"),
                () -> "Expected the Decimal-refinement rejection; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("not yet wired") || ex.getMessage().contains("sign"),
                () -> "Expected forward-pointing message; got: " + ex.getMessage());
    }

    @Test
    void integerArithmetic_stillUnaffected() throws Exception {
        // Adding Decimal must not perturb the integer path.
        assertEquals(7L, run("1 + 2 * 3"));
    }
}
