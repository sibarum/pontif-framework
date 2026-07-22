package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code evalInterval} — the interval evaluator over {@code AlgExpr} (docs/reliable-plotting.md,
 * slice 1). It computes a <b>sound enclosure</b> of {@code { f(x) : x ∈ [lo, hi] }}: a bounded
 * {@code Interval(ylo, yhi)}, an {@code Unbounded} column (a pole / ±∞ spill), or {@code Undefined}
 * (wholly out of domain). Soundness is the no-lie law at the pixel — the enclosure provably contains
 * the true curve — and inexact endpoints round OUTWARD so it is always a true superset. This is the
 * generalisation of {@code evalSafe} from a point to a column; no renderer is involved yet.
 */
class AlgebraIntervalTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private String run(String src) {
        return new PontifRunner().run(
                compiler.compileAlt(src, "interval.ptf"), Engine.INTERPRETER).text();
    }

    /** Classify the three outcomes — used by the domain/pole tests. */
    private static final String CLASSIFY = """
            requires pontif.algebra.{AlgExpr, Const, Param, Div, Mul, Sin, Log, Pow,
                                     Interval, Unbounded, Undefined, evalInterval}
            """;

    @Test
    void poleColumnIsUnbounded() {
        // 1/x over a column straddling 0: the divisor range contains 0 → Unbounded, found by the
        // arithmetic (no root-solving). This is how a pole surfaces.
        assertEquals("\"unbounded\"", run(CLASSIFY + """
                let e:AlgExpr = Div(Const(1.0), Param("x"))
                match evalInterval(e, -1.0, 1.0) {
                  [Interval(lo, hi)] -> "bounded"
                  [Unbounded]        -> "unbounded"
                  [Undefined]        -> "undefined"
                }
                """));
    }

    @Test
    void tameColumnEnclosesExactly() {
        // x*x over [2, 3]: both endpoints positive, so the enclosure is exact [4, 9] (no fattening).
        assertEquals("\"ok\"", run(CLASSIFY + """
                let e:AlgExpr = Mul(Param("x"), Param("x"))
                match evalInterval(e, 2.0, 3.0) {
                  [Interval(lo, hi)] -> match (lo == 4.0) {
                    [Bool:true]  -> match (hi == 9.0) { [Bool:true] -> "ok"  [Bool:false] -> "hi?" }
                    [Bool:false] -> "lo?"
                  }
                  [Unbounded] -> "unbounded"
                  [Undefined] -> "undefined"
                }
                """));
    }

    @Test
    void whollyOutOfDomainIsUndefined() {
        // log(x) over [-4, -1]: no real value anywhere on the column → Undefined (a true gap).
        assertEquals("\"undefined\"", run(CLASSIFY + """
                let e:AlgExpr = Log(Param("x"))
                match evalInterval(e, -4.0, -1.0) {
                  [Interval(lo, hi)] -> "bounded"
                  [Unbounded]        -> "unbounded"
                  [Undefined]        -> "undefined"
                }
                """));
    }

    @Test
    void domainEdgeTowardsMinusInfinityIsUnbounded() {
        // log(x) over [-1, 2]: the defined part touches 0, where log → −∞ → Unbounded (not Undefined:
        // the curve genuinely exists on (0, 2], it just runs off the bottom).
        assertEquals("\"unbounded\"", run(CLASSIFY + """
                let e:AlgExpr = Log(Param("x"))
                match evalInterval(e, -1.0, 2.0) {
                  [Interval(lo, hi)] -> "bounded"
                  [Unbounded]        -> "unbounded"
                  [Undefined]        -> "undefined"
                }
                """));
    }

    @Test
    void sinOverAWideColumnSaturatesToTheFullRange() {
        // sin(x) over [0, 10] spans more than a full period → the sound enclosure is exactly [-1, 1]
        // (the periodic-extrema rule: a max and a min are both enclosed).
        assertEquals("\"ok\"", run(CLASSIFY + """
                let e:AlgExpr = Sin(Param("x"))
                match evalInterval(e, 0.0, 10.0) {
                  [Interval(lo, hi)] -> match (lo == -1.0) {
                    [Bool:true]  -> match (hi == 1.0) { [Bool:true] -> "ok"  [Bool:false] -> "hi?" }
                    [Bool:false] -> "lo?"
                  }
                  [Unbounded] -> "unbounded"
                  [Undefined] -> "undefined"
                }
                """));
    }

    @Test
    void inexactQuotientRoundsOutwardToAProperInterval() {
        // 1/x over [3, 3] is 1/3, non-terminating: the endpoints must round OUTWARD so the enclosure
        // strictly contains 1/3 (lo < hi), never a false exact point. Soundness over tightness.
        assertEquals("\"widened\"", run(CLASSIFY + """
                let e:AlgExpr = Div(Const(1.0), Param("x"))
                match evalInterval(e, 3.0, 3.0) {
                  [Interval(lo, hi)] -> match (lo < hi) { [Bool:true] -> "widened"  [Bool:false] -> "point" }
                  [Unbounded]        -> "unbounded"
                  [Undefined]        -> "undefined"
                }
                """));
    }

    @Test
    void exactQuotientStaysAPoint() {
        // 1/x over [2, 2] is 0.5, terminating: exactness is preserved (lo == hi), the counterpart to
        // the outward-rounding test — we widen only when we must.
        assertEquals("\"point\"", run(CLASSIFY + """
                let e:AlgExpr = Div(Const(1.0), Param("x"))
                match evalInterval(e, 2.0, 2.0) {
                  [Interval(lo, hi)] -> match (lo == hi) { [Bool:true] -> "point"  [Bool:false] -> "widened" }
                  [Unbounded]        -> "unbounded"
                  [Undefined]        -> "undefined"
                }
                """));
    }

    @Test
    void sqrtAcrossZeroEnclosesTheDefinedPart() {
        // sqrt(x) = Pow(x, 0.5) over [-1, 4]: partially out of domain. The correctness rule keeps it a
        // bounded Interval of the DEFINED part ([0, 2]), NOT Undefined — a partial domain must not
        // punch a false gap. (Tightness at the boundary is subdivision's job, slice 3.)
        assertEquals("\"ok\"", run(CLASSIFY + """
                let e:AlgExpr = Pow(Param("x"), Const(0.5))
                match evalInterval(e, -1.0, 4.0) {
                  [Interval(lo, hi)] -> match (lo <= 0.0) {
                    [Bool:true]  -> match (hi >= 2.0) { [Bool:true] -> "ok"  [Bool:false] -> "hi?" }
                    [Bool:false] -> "lo?"
                  }
                  [Unbounded] -> "unbounded"
                  [Undefined] -> "undefined"
                }
                """));
    }
}
