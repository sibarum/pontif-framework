package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
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
 * Division and remainder, cross-type. {@code Int / Int} truncates toward zero
 * and {@code Int % Int} is the matching remainder — together an
 * information-conservative pair ({@code a == (a/b)*b + a%b}). {@code Decimal /
 * Decimal} rounds via DECIMAL128.
 */
class DivisionAltTest {

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
    void intDivision_truncatesTowardZero() throws Exception {
        assertEquals(3L, run("7 / 2"));
        assertEquals(-3L, run("-7 / 2"));
        assertEquals(0L, run("1 / 2"));
    }

    @Test
    void intRemainder_signOfDividend() throws Exception {
        assertEquals(1L, run("7 % 2"));
        assertEquals(-1L, run("-7 % 2"));
        assertEquals(0L, run("8 % 4"));
    }

    @Test
    void divAndMod_areInformationConservativePair() throws Exception {
        // a == (a/b)*b + a%b — and precedence: / % * are multiplicative, + lower.
        // 7 / 2 * 2 + 7 % 2 == 6 + 1 == 7.
        assertEquals(7L, run("7 / 2 * 2 + 7 % 2"));
        assertEquals(-7L, run("-7 / 2 * 2 + -7 % 2"));
    }

    @Test
    void decimalDivision_isRounded() throws Exception {
        assertDecimal("0.25", run("1.0 / 4.0"));
        assertDecimal("2.5", run("10.0 / 4.0"));
    }

    @Test
    void decimalDivision_nonTerminating_roundsToPrecision() throws Exception {
        // 1/3 has no finite decimal — DECIMAL128 gives 34 sig digits, no throw.
        Object r = run("1.0 / 3.0");
        assertTrue(r instanceof BigDecimal);
        BigDecimal d = (BigDecimal) r;
        assertTrue(d.compareTo(new BigDecimal("0.333")) > 0 && d.compareTo(new BigDecimal("0.334")) < 0,
                () -> "Expected ~0.333…, got " + d);
    }

    @Test
    void divisionThroughFunction_dispatches() throws Exception {
        assertEquals(2L, run("function half(x:Int):Int -> x / 2\nhalf(5)"));
        assertDecimal("2.5", run("function half(x:Decimal):Decimal -> x / 2.0\nhalf(5.0)"));
    }

    @Test
    void intDivisionByZero_isRuntimeError() {
        assertThrows(RuntimeCheckException.class, () -> run("5 / 0"));
    }

    @Test
    void intRemainderByZero_isRuntimeError() {
        assertThrows(RuntimeCheckException.class, () -> run("5 % 0"));
    }

    @Test
    void divisionInRefinementPredicate_isRejected() {
        // '/' and '%' aren't in the linear kernel — reject in predicate position.
        CompileException ex = assertThrows(CompileException.class, () ->
                run("function f(x:[Int:@/2 == 0]):Int -> x\n0"));
        assertTrue(ex.getMessage().contains("Division") || ex.getMessage().contains("linear"),
                () -> "Expected division-in-predicate rejection; got: " + ex.getMessage());
    }
}
