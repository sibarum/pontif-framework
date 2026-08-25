package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ==} / {@code !=} / {@code ~=} over AGGREGATE operands — a struct, a tuple, a
 * dictionary, a declared record shape, a nested struct, an enum case.
 *
 * <p>The rule these pin is already ruled, in two places that agree: {@code
 * IrInterpreter.dispatchOperatorSymbol} ("{@code ==}/{@code !=} stay built-in structural
 * equality" — arithmetic and ordering route to a user overload, equality never does) and
 * {@code docs/keyed.md}: "Native {@code ==} on structs is structural + nominal
 * ({@code RecordValue.equals}: same typeName + same members)". So the question here is
 * not what equality means but whether both engines mean it.
 *
 * <p>They did not. The interpreter evaluated all three ops as {@code Objects.equals};
 * Truffle's {@code Cmp.combine} ran a typed ladder (Char, String, Decimal) and then cast
 * to {@code Long}, so every aggregate operand died with an internal
 * {@code ClassCastException}. Nothing asked, because no test compared two aggregates.
 * Enum cases — landed two days before this was found — made it a bug someone would hit
 * on their first enum, {@code Color.Red == Color.Red} being the most natural thing to
 * write with one.
 *
 * <p>Every case therefore runs on BOTH engines and asserts they agree. The ordering test
 * below is the boundary pin: {@code <} on aggregates must keep being a COMPILE error, so
 * the new equality branch must not have widened into ordering.
 */
class AggregateEqualityTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    /** Runs on every engine and asserts they agree; returns the shared result. */
    private String run(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "eq.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected compile success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        String first = null;
        for (PontifRunner.Engine e : PontifRunner.Engine.values()) {
            String out = runner.run(r, e).text();
            if (first == null) {
                first = out;
            } else {
                final String expected = first;
                assertEquals(expected, out, () -> "engines disagree on the same program");
            }
        }
        return first;
    }

    private String reject(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "eq.ptf");
        return assertInstanceOf(PontifCompiler.CompileResult.Failed.class, r,
                "expected a compile rejection").error().text();
    }

    /** `==` on the two operands, rendered 1/0 — a Bool is not itself printable as a digit. */
    private String eq(String decls, String left, String right) {
        return run(decls + "match (" + left + " == " + right + ") { [Bool:true] -> 1  [_] -> 0 }");
    }

    // --- struct ----------------------------------------------------------------

    @Test
    void struct_equalByMembers() {
        assertEquals("1", eq("struct P(x:Int)\n", "P(1)", "P(1)"));
    }

    @Test
    void struct_unequalByMembers() {
        assertEquals("0", eq("struct P(x:Int)\n", "P(1)", "P(2)"));
    }

    @Test
    void struct_severalFields_allMustMatch() {
        assertEquals("1", eq("struct P(x:Int, y:Int)\n", "P(1, 2)", "P(1, 2)"));
        assertEquals("0", eq("struct P(x:Int, y:Int)\n", "P(1, 2)", "P(1, 3)"));
    }

    @Test
    void struct_notEqualsOperator() {
        assertEquals("1", run("""
                struct P(x:Int)
                match (P(1) != P(2)) { [Bool:true] -> 1  [_] -> 0 }
                """));
    }

    @Test
    void struct_approxIsEqualityWhenNoRoundingIsInPlay() {
        assertEquals("1", run("""
                struct P(x:Int)
                match (P(1) ~= P(1)) { [Bool:true] -> 1  [_] -> 0 }
                """));
    }

    // --- the other aggregate kinds ---------------------------------------------

    @Test
    void tuple_comparesSlotWise() {
        assertEquals("1", eq("", "{1, 2}", "{1, 2}"));
        assertEquals("0", eq("", "{1, 2}", "{1, 3}"));
    }

    @Test
    void dictionary_comparesByName() {
        assertEquals("1", eq("", "{a = 1, b = 2}", "{a = 1, b = 2}"));
        assertEquals("0", eq("", "{a = 1, b = 2}", "{a = 1, b = 3}"));
    }

    @Test
    void declaredRecordShape_comparesByName() {
        assertEquals("1", run("""
                let p:[{x:Int, y:Int}] = {x = 1, y = 2}
                let q:[{x:Int, y:Int}] = {x = 1, y = 2}
                match (p == q) { [Bool:true] -> 1  [_] -> 0 }
                """));
    }

    /** Structural all the way down — a nested struct member compares by its own members. */
    @Test
    void nestedStruct_comparesRecursively() {
        assertEquals("1", eq("struct I(n:Int)\nstruct O(i:I)\n", "O(I(1))", "O(I(1))"));
        assertEquals("0", eq("struct I(n:Int)\nstruct O(i:I)\n", "O(I(1))", "O(I(2))"));
    }

    // --- enum cases — the practically sharpest one ------------------------------

    @Test
    void enumCase_equalsItself() {
        assertEquals("1", run("""
                enum Color { Red
                  Green }
                match (Color.Red == Color.Red) { [Bool:true] -> 1  [_] -> 0 }
                """));
    }

    @Test
    void enumCase_differsFromAnotherCase() {
        assertEquals("0", run("""
                enum Color { Red
                  Green }
                match (Color.Red == Color.Green) { [Bool:true] -> 1  [_] -> 0 }
                """));
    }

    @Test
    void enumCase_throughABinding() {
        assertEquals("1", run("""
                enum Color { Red
                  Green }
                let c = Color.Green
                match (c != Color.Red) { [Bool:true] -> 1  [_] -> 0 }
                """));
    }

    // --- the boundary: ordering is NOT equality ---------------------------------

    /**
     * The fix covers the three equality ops only. Ordering an aggregate stays a compile
     * error — there is no structural {@code <}, and inventing one would be a language
     * change rather than a bug fix.
     */
    @Test
    void ordering_onAggregates_staysACompileError() {
        assertTrue(reject("""
                struct P(x:Int)
                match (P(1) < P(2)) { [Bool:true] -> 1  [_] -> 0 }
                """).contains("Operator '<' is not defined for (P, P)"),
                "ordering must stay rejected, with the operator-not-defined message");
    }

    // --- negative controls: the scalar ladders are untouched --------------------

    @Test
    void scalarEquality_isUnchanged() {
        assertEquals("1", run("match (1 == 1) { [Bool:true] -> 1  [_] -> 0 }"));
        assertEquals("0", run("match (1 == 2) { [Bool:true] -> 1  [_] -> 0 }"));
        assertEquals("1", run("match (true == true) { [Bool:true] -> 1  [_] -> 0 }"));
        assertEquals("1", run("match (\"a\" == \"a\") { [Bool:true] -> 1  [_] -> 0 }"));
        assertEquals("1", run("match ('a' == 'a') { [Bool:true] -> 1  [_] -> 0 }"));
    }

    /** Decimal equality is compareTo-based, so 2.0 == 2.00 — it must not become Object equality. */
    @Test
    void decimalEquality_staysCompareToBased() {
        assertEquals("1", run("match (2.0 == 2.00) { [Bool:true] -> 1  [_] -> 0 }"));
    }

    @Test
    void scalarOrdering_isUnchanged() {
        assertEquals("1", run("match (1 < 2) { [Bool:true] -> 1  [_] -> 0 }"));
        assertEquals("0", run("match (2 < 1) { [Bool:true] -> 1  [_] -> 0 }"));
        assertEquals("1", run("match (\"a\" < \"b\") { [Bool:true] -> 1  [_] -> 0 }"));
    }
}
