package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.PontifParser;
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
        IrModule module = PontifParser.parseModule(src, "m.ptf");
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

    @Test void typeStageIsACoercion_intToString() throws Exception {
        // James's model: [Type] is the coercion case of a production — @ := (Type:@).
        // [Int -> String] renders the Int via the built-in String coercion (a Cast),
        // not a type error.
        assertEquals("\"5\"", String.valueOf(run("""
                let show:[Int -> String]
                show(5)""")));
    }

    @Test void interleavedLet_inValueChain() throws Exception {
        // Free interleaving (the generalized model): a `let` mid-chain names the
        // current @; later productions read it while @ keeps advancing.
        //   @=5 -> base=5 -> @=5+5=10 -> @=10*2=20
        assertEquals(20L, run("""
                let f:[Int -> let base=@ -> @ + base -> @ * 2 -> Int]
                f(5)"""));
    }

    @Test void multiStageTypeFlow_intToStringThenString() throws Exception {
        // @ flows Int -> (concat) String -> (assert) String -> (concat) String.
        assertEquals("\"7!\"", String.valueOf(run("""
                let f:[Int -> @ + "" -> String -> @ + "!" -> String]
                f(7)""")));
    }

    @Test void closedPipeline_productionTerminus_synthesizesBody() throws Exception {
        // Full unification (James's model): the closed `let`-led clause no longer
        // needs an explicit `@==r` pin — the final production `r` IS the witness, so
        // the return sort `[let r = n*2 -> r]` synthesizes the body. Same fold as a
        // value chain; only the position projection (sort vs lambda) differs.
        assertEquals(14L, run("""
                function dbl(n:[Int]):[let r:Int = n * 2 -> r];
                dbl(7)"""));
    }

    @Test void letAscriptionAppliesClause_topLevel() throws Exception {
        // Unified ascription: a clause-typed let WITH a `= rhs` subject applies the
        // clause to the subject — the same apply-to-subject primitive as a function
        // return-clause. `12` enters as Int, the chain converts it to "12".
        assertEquals("\"12\"", String.valueOf(run("""
                let x:[Int -> @ + "" -> String] = 12
                x""")));
    }

    @Test void letAscriptionAppliesClause_nested() throws Exception {
        // Same rule inside a function body (nested let).
        assertEquals(50L, run("""
                function f(n:[Int]):Int ->
                  let y:[Int -> @ * 10 -> Int] = n
                  y
                f(5)"""));
    }

    @Test void letBindsClauseValue_whenNoSubject() throws Exception {
        // No `= rhs` subject → the clause binds AS A VALUE (the fragment), unchanged.
        assertEquals(8L, run("""
                let g:[Int -> @ + 3 -> Int]
                g(5)"""));
    }

    @Test void paramConversionClause_convertsArgumentOnEntry() throws Exception {
        // S7 — input mirror of return-as-transform: the caller passes the DOMAIN (Int);
        // inside the function `bar` is the codomain (the clause applied to the arg).
        assertEquals(6L, run("""
                function f(bar:[Int -> @ + 1 -> Int]):Int -> bar
                f(5)"""));
    }

    @Test void paramConversionClause_destructureAndConstruct() throws Exception {
        // James's example: caller passes a MyStruct; it is destructured to a,b and
        // converted to a ProprietaryType; `bar` in the body is that ProprietaryType.
        assertEquals(7L, run("""
                struct MyStruct(a:Int, b:Int)
                struct ProprietaryType(z:Int)
                function g(bar:[MyStruct.{a,b} -> ProprietaryType{z=a+b}]):Int -> bar.z
                g(MyStruct(3, 4))"""));
    }

    @Test void closedClauseWithNoTerminus_isCleanError() {
        // A clause that only binds and never produces @ is a clean parse error, not a crash.
        assertThrows(sibarum.pontif.parser.ParseException.class, () -> run("""
                let x:[let r:Int = 5];
                x"""));
    }

    @Test void spreadAscriptionChain_mapsElements() throws Exception {
        // `&s:[Int -> @*2 -> Int]` is the inline map face — the conversion-chain
        // spelling of `&s:[ (el:Int) -> el*2 ]`.
        assertEquals("{2, 4, 6, 8}", String.valueOf(run("""
                let s = {1, 2, 3, 4}
                &s:[Int -> @ * 2 -> Int]""")));
    }
}
