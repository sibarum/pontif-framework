package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reflectable math primitives in the dispatch-to-AST flow: selected {@code pontif.math} functions
 * may appear in an algebraic function's body and reflect (via {@code $f[Decimal].ast}) into
 * {@code pontif.algebra} AST nodes. The power family (sqrt/pow/exp2/inverseSqrt) reflects to the
 * exact {@code Pow} node; the transcendentals (sin/cos/tan/exp/log) reflect to dedicated nodes
 * evaluated in double precision, emitted with an honest number of significant digits.
 */
class AlgebraMathPrimitiveTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private String run(String src) {
        return new PontifRunner().run(
                compiler.compileAlt(src, "mathprim.ptf"), Engine.INTERPRETER).text();
    }

    @Test
    void sqrt_reflectsToPow_andEvalsExactly() {
        // sqrt is now allowed in an algebraic body; it reflects to Pow(x, 0.5) and evals exactly.
        assertEquals("true", run("""
                requires pontif.algebra.{eval}
                requires pontif.math.{sqrt}
                function f(x:Decimal):Decimal -> sqrt(x)
                assign proof f:Algebraic
                eval($f[Decimal].ast, 4.0) == 2.0
                """));
    }

    @Test
    void sqrt_reflectsToAPowNode_matchable() {
        // The reflected AST is genuinely a Pow node (not an inlined placeholder body).
        assertEquals("1", run("""
                requires pontif.algebra.{AlgExpr, Pow, eval}
                requires pontif.math.{sqrt}
                function f(x:Decimal):Decimal -> sqrt(x)
                assign proof f:Algebraic
                let e:AlgExpr = $f[Decimal].ast
                match e { [Pow(_, _)] -> 1  [_] -> 0 }
                """));
    }

    @Test
    void cos_reflectsToACosNode_evaluatedInDouble() {
        // cos(0) = 1 exactly; the node is a Cos node.
        assertEquals("true", run("""
                requires pontif.algebra.{AlgExpr, Cos, eval}
                requires pontif.math.{cos}
                function f(x:Decimal):Decimal -> cos(x)
                assign proof f:Algebraic
                eval($f[Decimal].ast, 0.0) == 1.0
                """));
    }

    @Test
    void sin_evalHasHonestSignificantDigits_notDecimal128() {
        // sin(1) in double precision -> 15 significant digits, NOT 34, and NOT the 17-digit artifact.
        String out = run("""
                requires pontif.algebra.{eval}
                requires pontif.math.{sin}
                function f(x:Decimal):Decimal -> sin(x)
                assign proof f:Algebraic
                eval($f[Decimal].ast, 1.0)
                """);
        java.math.BigDecimal v = new java.math.BigDecimal(out.trim());
        assertTrue(v.precision() <= 15, "double-eval result must not overstate precision: " + out);
        assertEquals(0, v.compareTo(new java.math.BigDecimal("0.841470984807897")),
                "sin(1) to 15 significant digits, got " + out);
    }

    @Test
    void combined_polynomialAndTranscendental_reflectsAndEvals() {
        // x^2 + cos(x): mixes exact arithmetic with a double-eval transcendental. At x=0 -> 1.
        assertEquals("true", run("""
                requires pontif.algebra.{eval}
                requires pontif.math.{cos}
                function f(x:Decimal):Decimal -> x*x + cos(x)
                assign proof f:Algebraic
                eval($f[Decimal].ast, 0.0) == 1.0
                """));
    }

    @Test
    void log_ofNonPositive_failsClosed() {
        // log(0) is -inf; the double path must fail closed rather than emit a non-finite Decimal.
        String out = run("""
                requires pontif.algebra.{eval}
                requires pontif.math.{log}
                function f(x:Decimal):Decimal -> log(x)
                assign proof f:Algebraic
                eval($f[Decimal].ast, 0.0)
                """);
        assertTrue(out.toLowerCase().contains("non-finite") || out.toLowerCase().contains("finite"),
                "log(0) should fail closed, got: " + out);
    }

    @Test
    void nonAlgebraicNativeCall_stillRejected() {
        // A pontif.math function NOT in the primitive registry is still rejected at the claim —
        // the whitelist is closed, not "any native is fine".
        var r = compiler.compileAlt("""
                requires pontif.math.{floor}
                function f(x:Decimal):Decimal -> floor(x)
                assign proof f:Algebraic
                $f[Decimal].ast
                """, "mathprim.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Failed.class, r,
                "floor is not an algebraic primitive; the claim must be rejected, got " + r);
    }
}
