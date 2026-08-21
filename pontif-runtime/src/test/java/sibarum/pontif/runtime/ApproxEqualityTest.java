package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ~=} — approximate equality, done right: the tolerance is DERIVED from
 * the language's own loss policy (one ulp at DECIMAL128's 34 significant
 * digits, scaled to magnitude), never chosen. {@code ~=} coincides with
 * {@code ==} wherever no rounding exists; narrows and predicates stay exact
 * (the proof layer never forgives).
 */
class ApproxEqualityTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String src) {
        return runner.run(compiler.compile(src, "t.ptf"), Engine.INTERPRETER);
    }

    @Test
    void roundingArtifact_failsExact_passesApprox() {
        // The divergence witness: 1/3*3 gives 34 nines under DECIMAL128.
        assertEquals("false", run("1.0 / 3.0 * 3.0 == 1.0").text());
        assertEquals("true", run("1.0 / 3.0 * 3.0 ~= 1.0").text());
    }

    @Test
    void genuinelyDifferentValues_fail() {
        assertEquals("false", run("1.5 ~= 1.6").text());
        assertEquals("false", run("1.0 ~= 1.001").text());
    }

    @Test
    void exactlyEqual_shortCircuits_andNoRoundingMeansExact() {
        assertEquals("true", run("2.0 ~= 2.00").text());
        assertEquals("true", run("5 ~= 5").text());   // Int: ~= is ==
        assertEquals("false", run("5 ~= 6").text());
        assertEquals("true", run("1 ~= 1.0").text()); // promotes, exact
    }

    @Test
    void relativeToleranceHasNoJurisdictionAtZero() {
        // A tiny nonzero value can't be assumed a rounding artifact of 0.
        assertEquals("false",
                run("0.0000000000000000000000000000000000001 ~= 0.0").text());
    }

    @Test
    void ternionAlgebraicIdentity_holdsUnderApprox() {
        // The case that motivated ~=: t * t.inv() should be one (in n).
        RunResult r = run("""
                struct Ternion(z:Decimal, n:Decimal, w:Decimal)
                method Ternion.inv():Ternion ->
                  match this {
                    [Ternion(z, 0, w)] -> Ternion(w, 0, z+1)
                    [Ternion(z, n, w)] -> Ternion(w, 1.0/n, z)
                  }
                function *(left:Ternion, right:Ternion):Ternion -> (
                  let z = left.z*right.z + left.z*right.n + left.n*right.z
                  let n = left.n*right.n + left.z*right.w + left.w*right.z
                  let w = left.w*right.w + left.w*right.n + left.n*right.w
                  Ternion(z, n, w)
                )
                let u = Ternion(0, 1.2, 0)
                let p = u * u.inv()
                p.n ~= 1.0
                """);
        assertEquals("true", r.text(), "t * t.inv() should be ~= one in n");
    }

    @Test
    void approxInRefinementPredicate_isRejected() {
        RunResult r = run("function f(x:[Decimal:@~=1]):Decimal -> x\n0");
        assertTrue(r.isError(), "~= must be rejected in sort position");
        assertTrue(r.text().contains("narrow") || r.text().contains("~="),
                () -> "expected the exact-sorts rejection; got: " + r.text());
    }
}
