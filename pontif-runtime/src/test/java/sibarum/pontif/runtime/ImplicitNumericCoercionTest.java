package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Implicit {@code Int → Decimal} coercion at value boundaries (NumericCoercion) —
 * the value-level companion to {@code DecimalPromotion}'s literal rewrite. The
 * closed lossless tower is the one coercion Pontif keeps implicit
 * (docs/dispatch-unification.md → "Coercion"); a non-literal Int value meeting a
 * declared Decimal is widened by an inserted {@code Int → Decimal} cast at the
 * let-claim, return, call-argument, and record-member boundaries. Every case runs
 * on BOTH engines — the interpreter evaluates the cast, the Truffle backend lowers
 * it to {@code IntToDecimalNode}.
 */
class ImplicitNumericCoercionTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private void bothEngines(String src, String expected) {
        for (Engine engine : Engine.values()) {
            RunResult r = runner.run(compiler.compile(src, "t.ptf"), engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals(expected, r.text(), engine.toString());
        }
    }

    @Test
    void traitMethodReturn_widensIntFieldToDecimal() {
        // The reported case: asDecimal() -> this.value returns an Int field where a
        // Decimal is declared. The return boundary now widens it.
        bothEngines("""
                trait ValueLiteral { asDecimal:[Method():[Decimal:@!=0]] }
                struct IntegerLiteral(value:Int)
                assign trait IntegerLiteral:ValueLiteral {
                  asDecimal():[Decimal:@!=0] -> this.value
                }
                IntegerLiteral(12).asDecimal()""", "12.0");
    }

    @Test
    void letClaim_widensIntCallResult() {
        bothEngines("function f():Int -> 5\nlet x:Decimal = f()\nx", "5.0");
    }

    @Test
    void functionReturn_widensIntBody() {
        bothEngines("function g():Decimal -> 7\ng()", "7.0");
    }

    @Test
    void callArgument_widensIntIntoDecimalParam() {
        bothEngines("function h(d:Decimal):Decimal -> d\nfunction f():Int -> 5\nh(f())", "5.0");
    }

    @Test
    void recordMember_widensIntIntoDecimalField() {
        bothEngines("struct P(d:Decimal)\nfunction f():Int -> 5\nP(f()).d", "5.0");
    }

    @Test
    void widenedValue_participatesInDecimalArithmetic() {
        // The widened value is a real Decimal downstream, not a tagged Int.
        bothEngines("function f():Int -> 5\nlet x:Decimal = f()\nx + 1.5", "6.5");
    }

    @Test
    void noCoercion_whenDeclaredSortIsInt() {
        // An Int value meeting a declared Int is untouched — no spurious widening.
        bothEngines("function f():Int -> 5\nlet x:Int = f()\nx", "5");
    }

    @Test
    void decimalToInt_isNotImplicit() {
        // The reverse (lossy) direction stays out: a Decimal value does not silently
        // become an Int. The declared Int either rejects or keeps it a Decimal — in
        // no case is this a silent Decimal→Int narrowing to "5".
        RunResult r = runner.run(compiler.compile(
                "function f():Decimal -> 5.0\nlet x:Int = f()\nx", "t.ptf"), Engine.INTERPRETER);
        // Whatever the verdict, it must NOT be a silently narrowed Int "5".
        boolean silentlyNarrowed = !r.isError() && r.text().equals("5");
        assertFalse(silentlyNarrowed, () -> "Decimal must not implicitly narrow to Int; got: " + r.text());
    }
}
