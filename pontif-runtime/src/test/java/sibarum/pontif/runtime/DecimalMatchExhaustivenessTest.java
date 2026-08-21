package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Match totality over the {@code Decimal} domain. The predicate kernel now
 * reasons over dense (real) intervals, so an order-total split needs no {@code _}
 * default — e.g. a field refined {@code [Decimal:@!=0]} matched by {@code @>0}
 * and {@code @<0} is exhaustive by construction. Genuinely uncovered regions
 * (including dense gaps that would be closed over Int) are still rejected.
 */
class DecimalMatchExhaustivenessTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        CompileResult r = compiler.compile(src, "decmatch.ptf");
        CompileResult.Compiled c = assertInstanceOf(
                CompileResult.Compiled.class, r, () -> "expected compile success; got " + r);
        PontifRunner.RunResult rr = runner.run(c.program(), Engine.INTERPRETER);
        assertTrue(!rr.isError(), () -> "run error: " + rr.text());
        return rr.text();
    }

    private String reject(String src) {
        CompileResult r = compiler.compile(src, "decmatch.ptf");
        return ((CompileResult.Failed) assertInstanceOf(
                CompileResult.Failed.class, r, "expected a compile rejection")).error().text();
    }

    @Test void nonzeroDecimalField_strictSplit_needsNoDefault() {
        assertEquals("\"neg\"", run("""
                struct R(exp:[Decimal:@!=0])
                function classify(r:R):String -> match r.exp {
                  [Decimal:@>0] -> "pos"
                  [Decimal:@<0] -> "neg"
                }
                classify(R(-2.5))"""));
    }

    @Test void fullDecimal_strictSplit_missesZero_isRejected() {
        String err = reject("""
                function f(x:Decimal):String -> match x {
                  [Decimal:@>0] -> "p"
                  [Decimal:@<0] -> "n"
                }
                f(1.0)""");
        assertTrue(err.contains("not exhaustive"),
                () -> "expected a non-exhaustive rejection naming the gap; got: " + err);
    }

    @Test void fullDecimal_closedSplit_isExhaustive() {
        assertEquals("\"p\"", run("""
                function f(x:Decimal):String -> match x {
                  [Decimal:@>=0] -> "p"
                  [Decimal:@<0] -> "n"
                }
                f(1.0)"""));
    }

    /** The dense discriminator: total over Int, NOT over Decimal (gap (0,1)). */
    @Test void integerAdjacentSplit_isTotalOverInt_butNotDecimal() {
        assertEquals("\"p\"", run("""
                function f(x:Int):String -> match x {
                  [Int:@>=1] -> "p"
                  [Int:@<=0] -> "n"
                }
                f(3)"""));
        String err = reject("""
                function f(x:Decimal):String -> match x {
                  [Decimal:@>=1] -> "p"
                  [Decimal:@<=0] -> "n"
                }
                f(3.0)""");
        assertTrue(err.contains("not exhaustive"),
                () -> "expected the dense gap (0,1) to be rejected; got: " + err);
    }
}
