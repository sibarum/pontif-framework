package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity: identical source produces identical results through both engines. The interpreter is
 * exercised on its own in {@link PontifRunnerTest} and {@link LambdaParserIntegrationTest};
 * this file adds the Truffle path for the same scenarios, and compiles ONCE to run both.
 *
 * <p>Ported from the S-expression syntax when that parser was decommissioned. One case changed
 * shape on the way: the original provoked a Truffle runtime error with a non-total match, which
 * no longer compiles — totality is a compile-time obligation — so division by zero stands in.
 */
class PontifRunnerTruffleTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult runTruffle(String source, String name) {
        return runner.run(compiler.compile(source, name), Engine.TRUFFLE);
    }

    private RunResult runInterp(String source, String name) {
        return runner.run(compiler.compile(source, name), Engine.INTERPRETER);
    }

    private String truffleValue(String source, String name) {
        RunResult r = runTruffle(source, name);
        assertFalse(r.isError(), () -> "expected success; got: " + r.text());
        return r.text();
    }

    private static final String FACTORIAL = """
            function factorial(n:[Int:@==0]):Int -> 1
            function factorial(n:[Int:@>0]):Int -> n * factorial(n - 1)
            """;

    @Test
    void arithmetic_truffle() {
        assertEquals("7", truffleValue("1 + 2 * 3", "t.ptf"));
    }

    @Test
    void factorial_truffle() {
        assertEquals("720", truffleValue(FACTORIAL + "factorial(6)", "factorial.ptf"));
    }

    @Test
    void match_truffle() {
        assertEquals("7", truffleValue("""
                function abs(n:Int):Int -> match n
                  [@>=0] -> n
                  [@<0]  -> 0 - n
                abs(0 - 7)
                """, "abs.ptf"));
    }

    @Test
    void inlineClauseInvoke_truffle() {
        assertEquals("6", truffleValue("[(x:Int) -> x + 1](5)", "t.ptf"));
    }

    @Test
    void closureCapture_truffle() {
        assertEquals("8", truffleValue("""
                function addN(n:Int):[Method(Int):Int] -> [(x:Int) -> x + n]
                let add5 = addN(5)
                add5(3)
                """, "closure.ptf"));
    }

    @Test
    void mutualRecursionThroughDispatchOverloads() {
        // isEven(4) → isOdd(3) → isEven(2) → isOdd(1) → isEven(0) = 1. Kept from the S-expr
        // factorial suite, which was the only place mutual recursion across two overload sets
        // was exercised on both engines; the rest of that file is covered by the cases here.
        String src = """
                function isEven(n:[Int:@==0]):Int -> 1
                function isEven(n:[Int:@>0]):Int -> isOdd(n - 1)
                function isOdd(n:[Int:@==0]):Int -> 0
                function isOdd(n:[Int:@>0]):Int -> isEven(n - 1)
                isEven(4)
                """;
        assertEquals("1", truffleValue(src, "evenodd.ptf"));
        assertEquals(runInterp(src, "evenodd.ptf").text(), runTruffle(src, "evenodd.ptf").text());
    }

    @Test
    void runtimeError_truffle_carriesOrigin() {
        // Division by zero — the original used a non-total match, which is a compile error now.
        RunResult r = runTruffle("function f(x:Int):Int -> 10 / x\nf(0)", "divzero.ptf");
        assertTrue(r.isError(), () -> "expected a runtime error; got: " + r.text());
        assertTrue(r.origin().isPresent(), "expected origin on runtime error");
    }

    // --- compile ONCE, run both engines against the same artifact -----------------

    @Test
    void parityCheck_factorial() {
        PontifCompiler.CompileResult compiled = compiler.compile(FACTORIAL + "factorial(5)", "x.ptf");
        RunResult interp = runner.run(compiled, Engine.INTERPRETER);
        RunResult truffle = runner.run(compiled, Engine.TRUFFLE);
        assertFalse(interp.isError(), () -> interp.text());
        assertFalse(truffle.isError(), () -> truffle.text());
        assertEquals(interp.text(), truffle.text(), "engines disagree");
        assertEquals("120", interp.text());
    }

    @Test
    void parityCheck_closure() {
        String src = """
                let n = 10
                let f:[Method(Int):Int] = [(x:Int) -> x + n]
                f(5)
                """;
        assertEquals(runInterp(src, "x.ptf").text(), runTruffle(src, "x.ptf").text());
    }
}
