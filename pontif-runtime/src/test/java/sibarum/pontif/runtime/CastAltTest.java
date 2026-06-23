package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.AltParser;
import sibarum.pontif.parser.ParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for the explicit-coercion cast {@code (Type:value)}
 * (docs/dispatch-unification.md → "Coercion"), slice 1: the built-in renders to
 * {@code String} (Int/Decimal/Char/Bool/String → String), Pontif's answer to
 * implicit promotion. The motivating consumer is {@code let n:String = 12},
 * correctly a type mismatch, whose fix is {@code (String:12)}.
 *
 * <p>Also guards that the parser's cast detection does not steal ordinary
 * parenthesized groupings, tuples, or constructor calls (a complete sort
 * followed by {@code :} is the only thing that reads as a cast). Unsupported
 * coercions fail closed at eval, honoring the cast law's fabricate-never.
 */
class CastAltTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = AltParser.parseModule(src, "t.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    private void assertString(String content, Object actual) {
        assertTrue(actual instanceof StringValue,
                () -> "Expected a StringValue, got " + (actual == null ? "null" : actual.getClass().getSimpleName()));
        assertEquals(content, ((StringValue) actual).content());
    }

    @Test
    void rendersIntToString() throws Exception {
        assertString("12", run("(String:12)"));
        assertString("0", run("(String:0)"));
    }

    @Test
    void rendersDecimalPlain_noScientificNotation() throws Exception {
        assertString("1.5", run("(String:1.5)"));
        assertString("0.001", run("(String:0.001)"));
    }

    @Test
    void rendersChar() throws Exception {
        assertString("a", run("(String:'a')"));
    }

    @Test
    void rendersBool() throws Exception {
        assertString("true", run("(String:true)"));
        assertString("false", run("(String:false)"));
    }

    @Test
    void stringTargetIsIdentity() throws Exception {
        assertString("hi", run("(String:\"hi\")"));
    }

    @Test
    void motivatingConsumer_castSatisfiesAStringClaim() throws Exception {
        // `let n:String = 12` is (correctly) a type mismatch; the cast is the fix
        // and discharges the claim, so the bound value is a real String.
        assertString("n=42", run("""
                let n:String = (String:42)
                "n=" + n
                """));
    }

    @Test
    void castNestsInsideAConcatChain() throws Exception {
        assertString("x=5", run("\"x=\" + (String:5)"));
    }

    // --- The parser must not steal ordinary parenthesized forms ---

    @Test
    void grouping_stillParses() throws Exception {
        assertEquals(3L, run("(1 + 2)"));
    }

    @Test
    void tuple_stillParses() throws Exception {
        // (a, b) is a tuple literal, destructured here to confirm it is not a cast.
        assertEquals(8L, run("let [{a, b}] = {3, 5} a + b"));
    }

    @Test
    void constructorCallInParens_stillParses() throws Exception {
        // `(Point(1, 2))` starts with a Capitalized name but has no top-level
        // colon, so it must read as a grouped constructor call, not a cast.
        assertEquals(2L, run("""
                struct Point(x:Int, y:Int)
                (Point(1, 2)).y
                """));
    }

    // --- Fail closed (the cast law's fabricate-never) ---

    @Test
    void castOfUnrenderableValueToString_failsClosed() throws Exception {
        RuntimeCheckException e = assertThrows(RuntimeCheckException.class, () -> run("""
                struct Point(x:Int, y:Int)
                (String:Point(1, 2))
                """));
        assertTrue(e.getMessage().contains("cast") || e.getMessage().contains("String"),
                () -> e.getMessage());
    }

    @Test
    void castToTargetWithNoCoercion_failsClosed() {
        // No `cast Int:(…)` is defined, so a non-String target fails closed
        // (fabricate-never) — now reported as a missing coercion.
        RuntimeCheckException e = assertThrows(RuntimeCheckException.class,
                () -> run("(Int:\"abc\")"));
        assertTrue(e.getMessage().contains("No coercion") || e.getMessage().contains("cast Int"),
                () -> e.getMessage());
    }
}
