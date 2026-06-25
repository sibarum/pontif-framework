package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.AltParser;
import sibarum.pontif.parser.ParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * docs/arrows.md — "The unified clause-chain", slice S1: the anonymous-{@code @}
 * conversion chain {@code [ A -> @… -> B ]} as a runnable one-input transform.
 * A chain is a fragment whose input is the anonymous {@code @} ("a named binder is
 * just a named {@code @}") — it lowers to the same {@link sibarum.pontif.ir.IrExpr.Lambda}
 * / {@code Closure} a {@code [ (el:A) -> … ]} fragment does.
 */
class ClauseChainTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = AltParser.parseModule(src, "m.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        CompiledModule compiled = new IrCompiler(simp).compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    @Test void intIncrementChain_appliedDirectly() throws Exception {
        assertEquals(6L, run("""
                let inc:[Int -> @ + 1 -> Int]
                inc(5)"""));
    }

    @Test void intToStringChain_viaEmptyConcat() throws Exception {
        // The doc's `@+""` int->string conversion (a String operand makes `+` concatenate).
        assertEquals("\"12\"", String.valueOf(run("""
                let show:[Int -> @ + "" -> String]
                show(12)""")));
    }

    @Test void chainPassedToMethodParam_andInvoked() throws Exception {
        // A chain is a Method-sorted value, passable exactly like a named-binder fragment.
        assertEquals(10L, run("""
                function applyTo( f:[Method(Int):Int], x:Int ):Int -> f(x)
                let dbl:[Int -> @ * 2 -> Int]
                applyTo(dbl, 5)"""));
    }

    @Test void multiStageChain_threadsAt() throws Exception {
        // Two conversions in sequence: @ is threaded through both.
        assertEquals(21L, run("""
                let f:[Int -> @ + 1 -> @ * 3 -> Int]
                f(6)"""));
    }

    @Test void returnTypeAsTransform_function() throws Exception {
        // docs/arrows.md S2: the return clause CONVERTS the body result — bar*2 = 24,
        // then `@+""` renders it to the string "24".
        assertEquals("\"24\"", String.valueOf(run("""
                function foo(bar:[Int]):[Int -> @ + "" -> String] -> bar * 2
                foo(12)""")));
    }

    @Test void returnTypeAsTransform_intToInt() throws Exception {
        // A return transform that stays in Int: body 5, +100 -> 105.
        assertEquals(105L, run("""
                function f(x:[Int]):[Int -> @ + 100 -> Int] -> x
                f(5)"""));
    }

    @Test void stageTypeMismatch_isCompileError() {
        // docs/arrows.md S4: the running @ type is RELIABLY Int (the input), and the
        // String checkpoint follows with no conversion bridging them — a provable lie,
        // rejected at parse. (After a conversion the type is inferred and the check
        // abstains, per the no-lie law — see multiStageTypeFlow.)
        assertThrows(sibarum.pontif.parser.ParseException.class, () -> run("""
                let bad:[Int -> String]
                bad(1)"""));
    }

    @Test void multiStageTypeFlow_intToStringThenString() throws Exception {
        // @ flows Int -> (concat) String -> (assert) String -> (concat) String.
        assertEquals("\"7!\"", String.valueOf(run("""
                let f:[Int -> @ + "" -> String -> @ + "!" -> String]
                f(7)""")));
    }

    @Test void spreadAscriptionChain_mapsElements() throws Exception {
        // `&s:[Int -> @*2 -> Int]` is the inline map face — the conversion-chain
        // spelling of `&s:[ (el:Int) -> el*2 ]`.
        assertEquals("{2, 4, 6, 8}", String.valueOf(run("""
                let s = {1, 2, 3, 4}
                &s:[Int -> @ * 2 -> Int]""")));
    }
}
