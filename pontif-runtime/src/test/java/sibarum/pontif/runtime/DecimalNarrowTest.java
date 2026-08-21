package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decimal's three narrows — sign, range, equality-up-to-precision — through
 * the production compile path (return-verification gate included).
 *
 * <p>The headline is the <b>paired discreteness guard</b>: the same source
 * shape {@code @>0 → @>=1} is PROVABLE over Int (integer discreteness,
 * {@code BoundAnalysis}) and must be REJECTED over Decimal ({@code 0.5} is the
 * counterexample). The Int/Decimal discharge routing is the boundary; if the
 * Decimal half of this pair ever starts compiling, integer-strict reasoning
 * has leaked into the dense domain.
 */
class DecimalNarrowTest {

    private final PontifCompiler compiler = new PontifCompiler();

    // --- The paired discreteness guard --------------------------------------

    @Test
    void discretenessPair_intAccepts() {
        CompileResult r = compiler.compile(
                "module m\nfunction f(x:[Int:@>0]):[Int:@>=1] -> x\nf(5)", "pair-int.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "Int discreteness (>0 ⟹ >=1) should prove; got " + r);
    }

    @Test
    void discretenessPair_decimalRejects() {
        CompileResult r = compiler.compile(
                "module m\nfunction g(x:[Decimal:@>0]):[Decimal:@>=1] -> x\ng(5.0)", "pair-dec.ptf");
        CompileResult.Failed f = assertInstanceOf(CompileResult.Failed.class, r,
                "Decimal @>0 must NOT imply @>=1 (0.5 is the counterexample) — "
                        + "integer-strictness has leaked into the dense domain");
        assertTrue(f.error().text().contains("Cannot prove"),
                () -> "unexpected message: " + f.error().text());
    }

    // --- Sign ---------------------------------------------------------------

    @Test
    void signNarrow_fromPositiveLiteral_proves() {
        CompileResult r = compiler.compile(
                "module m\nfunction pos():[Decimal:@>0] -> 1.5\npos()", "sign-lit.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r, () -> "got " + r);
    }

    @Test
    void signNarrow_fromSignedParam_proves() {
        CompileResult r = compiler.compile(
                "module m\nfunction id(x:[Decimal:@>0]):[Decimal:@>0] -> x\nid(1.5)", "sign-id.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r, () -> "got " + r);
    }

    @Test
    void signNarrow_falseLiteral_rejects() {
        CompileResult r = compiler.compile(
                "module m\nfunction bad():[Decimal:@>0] -> -1.5\nbad()", "sign-bad.ptf");
        assertInstanceOf(CompileResult.Failed.class, r,
                "-1.5 cannot satisfy @>0; the gate must reject");
    }

    // --- Range (incl. thresholds as degenerate ranges) -----------------------

    @Test
    void rangeNarrow_paramToSameRange_proves() {
        CompileResult r = compiler.compile(
                "module m\nfunction clamp(x:[Decimal:@>=0 & @<=1]):[Decimal:@>=0 & @<=1] -> x\nclamp(0.5)",
                "range-id.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r, () -> "got " + r);
    }

    @Test
    void rangeNarrow_literalInRange_proves() {
        CompileResult r = compiler.compile(
                "module m\nfunction half():[Decimal:@>=0 & @<=1] -> 0.5\nhalf()", "range-lit.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r, () -> "got " + r);
    }

    @Test
    void rangeNarrow_literalOutOfRange_rejects() {
        CompileResult r = compiler.compile(
                "module m\nfunction over():[Decimal:@>=0 & @<=1] -> 1.5\nover()", "range-bad.ptf");
        assertInstanceOf(CompileResult.Failed.class, r,
                "1.5 is outside [0,1]; the gate must reject");
    }

    @Test
    void thresholdNarrow_denseImplication_proves() {
        // x>=1.5 ⟹ x>0 — dense-valid order implication over decimal bounds,
        // through the BigDecimal-generalized Refinements.implies.
        CompileResult r = compiler.compile(
                "module m\nfunction loosen(x:[Decimal:@>=1.5]):[Decimal:@>0] -> x\nloosen(2.0)",
                "threshold.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r, () -> "got " + r);
    }

    // --- Equality up-to-precision --------------------------------------------

    @Test
    void equalityNarrow_exactLiteral_proves() {
        CompileResult r = compiler.compile(
                "module m\nfunction pi():[Decimal:@==3.14] -> 3.14\npi()", "eq-lit.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r, () -> "got " + r);
    }

    @Test
    void equalityNarrow_isUpToScale() {
        // 2.00 == 2.0 by compareTo — equality is up-to-precision, not bitwise.
        CompileResult r = compiler.compile(
                "module m\nfunction two():[Decimal:@==2.0] -> 2.00\ntwo()", "eq-scale.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r, () -> "got " + r);
    }

    @Test
    void equalityNarrow_wrongValue_rejects() {
        CompileResult r = compiler.compile(
                "module m\nfunction off():[Decimal:@==2.0] -> 2.5\noff()", "eq-bad.ptf");
        assertInstanceOf(CompileResult.Failed.class, r,
                "2.5 is not == 2.0; the gate must reject");
    }
}
