package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The operator-completeness mandate: no operator application reaches runtime
 * undefined — "no applicable overload" is a compile error instead. Covers the
 * built-in primitive tier (Step A.1), concrete-struct overloads (A.2), and the
 * trait-typed-operand rule (Step C).
 */
class OperatorCompletenessTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    /** Asserts the source fails to compile and returns the error message. */
    private String rejects(String src) {
        CompileResult r = compiler.compile(src, "t.ptf");
        assertInstanceOf(CompileResult.Failed.class, r, () -> "expected a compile error for: " + src);
        return ((CompileResult.Failed) r).error().text();
    }

    /** Asserts the source compiles and runs, returning its value text. */
    private String runs(String src) {
        CompileResult r = compiler.compile(src, "t.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r, () -> "expected success; got: "
                + (r instanceof CompileResult.Failed f ? f.error().text() : "?"));
        return runner.run(r, Engine.INTERPRETER).text();
    }

    // --- A.1: built-in primitive operators ---

    @Test void charArithmetic_rejectedAtCompileTime() {
        assertTrue(rejects("'a' + 'b'").contains("not defined for (Char, Char)"));
    }

    @Test void stringArithmetic_rejectedAtCompileTime() {
        assertTrue(rejects("\"a\" * \"b\"").contains("concatenates"));
    }

    @Test void boolArithmetic_rejectedAtCompileTime() {
        assertTrue(rejects("true + false").contains("not defined for (Bool, Bool)"));
    }

    @Test void intArithmetic_runs() {
        assertEquals("3", runs("1 + 2"));
    }

    // --- A.2: concrete-struct operators ---

    private static final String MONEY = "struct Money(c:Int)\n";

    @Test void structOperatorWithoutOverload_rejectedAtCompileTime() {
        assertTrue(rejects(MONEY + "Money(1) + Money(2)").contains("not defined for (Money, Money)"));
    }

    @Test void structOperatorWithOverload_runs() {
        assertEquals("3", runs(MONEY
                + "function +(a:Money, b:Money):Money -> Money(a.c + b.c)\n"
                + "(Money(1) + Money(2)).c"));
    }

    @Test void structEquality_isAlwaysDefined() {
        assertEquals("true", runs("struct P(x:Int)\nP(1) == P(1)"));
    }

    // --- Step C: trait-typed operands ---

    private static final String NUMERIC =
            "trait Numeric{ +:[Dispatch(this.type, this.type):this.type] }\n"
            + "struct Vec(x:Int, y:Int)\n"
            + "function +(a:Vec, b:Vec):Vec -> Vec(a.x + b.x, a.y + b.y)\n"
            + "assign trait Vec:Numeric { }\n";

    @Test void bareTraitTypedOperand_rejectedAtCompileTime() {
        String e = rejects(NUMERIC
                + "function add(a:Numeric, b:Numeric):Numeric -> a + b\n"
                + "add(Vec(1, 2), Vec(3, 4)).x");
        assertTrue(e.contains("trait-typed operand 'Numeric'"), () -> e);
        assertTrue(e.contains("[type E:Numeric]"), () -> e);   // points at the total form
    }

    @Test void parametricBound_isTotal_andRuns() {
        assertEquals("4", runs(NUMERIC
                + "function sum[type E:Numeric](a:E, b:E):E -> a + b\n"
                + "sum(Vec(1, 2), Vec(3, 4)).x"));
    }

    @Test void bareTraitTypedEquality_isAllowed() {
        assertEquals("true", runs(NUMERIC
                + "function same(a:Numeric, b:Numeric):Bool -> a == b\n"
                + "same(Vec(1, 2), Vec(1, 2))"));
    }
}
