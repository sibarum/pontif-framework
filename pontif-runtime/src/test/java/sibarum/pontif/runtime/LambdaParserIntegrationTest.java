package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for closures: source → parser → IrCompiler → IrInterpreter.
 *
 * <p>Ported from the S-expression syntax when that parser was decommissioned. The original
 * exercised its unified {@code call} form, which handled both named-function invocation and
 * value-level closure invocation and picked at runtime by whether the name was locally bound.
 * Pontif spells application the same way in both cases — {@code f(x)} — so what is worth
 * pinning is the behavior underneath: an inline clause applies, a bound one invokes, a closure
 * captures, and a function may return one.
 *
 * <p>Two cases came out BETTER in the port and are noted where they appear: applying a literal
 * is a compile error here rather than a runtime one, and a same-named binding of a different
 * arity does not shadow a function, it joins its overloads.
 */
class LambdaParserIntegrationTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String source, String name) {
        return runner.run(compiler.compile(source, name), PontifRunner.Engine.INTERPRETER);
    }

    private String value(String source, String name) {
        RunResult r = run(source, name);
        assertFalse(r.isError(), () -> "expected success; got: " + r.text());
        return r.text();
    }

    @Test
    void inlineClauseApplied_yieldsExpectedValue() {
        assertEquals("6", value("[(x:Int) -> x + 1](5)", "inline.ptf"));
    }

    @Test
    void boundClauseInvokedByName() {
        assertEquals("49", value("""
                let f:[Method(Int):Int] = [(x:Int) -> x * x]
                f(7)
                """, "letLambda.ptf"));
    }

    @Test
    void closureCapturesEnclosingBinding() {
        assertEquals("15", value("""
                let n = 10
                let f:[Method(Int):Int] = [(x:Int) -> x + n]
                f(5)
                """, "closure.ptf"));
    }

    @Test
    void higherOrder_namedFunctionReturnsAClosure() {
        // `addN` resolves through dispatch; `add5` is a local binding holding the closure it
        // returned. Both spellings of application are the same syntax.
        assertEquals("8", value("""
                function addN(n:Int):[Method(Int):Int] -> [(x:Int) -> x + n]
                let add5 = addN(5)
                add5(3)
                """, "addN.ptf"));
    }

    @Test
    void multiArgClauseInvokedWithSeveralArgs() {
        assertEquals("12", value("[(x:Int, y:Int) -> x * y](3, 4)", "multiArg.ptf"));
    }

    @Test
    void aBindingOfDifferentArityJoinsTheOverloadsRatherThanShadowing() {
        // The S-expr version asserted an ERROR here: its `let factorial = 5` shadowed the
        // declared function outright, so `(call factorial 3)` tried to invoke a Long. In
        // Pontif a top-level binding is a 0-arg member of the same name, so a 1-argument call
        // still resolves to the 1-parameter function. Nothing is shadowed and nothing throws.
        assertEquals("999", value("""
                function factorial(n:Int):Int -> 999
                let factorial = 5
                factorial(3)
                """, "shadow.ptf"));
    }

    @Test
    void applyingALiteral_isACompileError() {
        // Better than the S-expr behavior, which parsed it and failed at eval: a literal is
        // statically known not to be callable, so it is rejected before the program runs.
        PontifCompiler.CompileResult r = compiler.compile("5(1)", "bad.ptf");
        String err = ((PontifCompiler.CompileResult.Failed) r).error().text();
        assertTrue(err.contains("not callable"), () -> "expected a not-callable error; got: " + err);
    }
}
