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
import sibarum.pontif.parser.PontifParser;
import sibarum.pontif.parser.ParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for the {@code String} value slice (the first Char
 * <em>collection</em>, Slice 1 — storage-side value/literal): double-quoted
 * literals (full Unicode, escapes {@code \n \t \" \\}), lexicographic
 * code-point ordering and equality, dispatch over {@code String}-typed params,
 * and the fail-closed fences — no arithmetic on strings, no String/Char and no
 * String/Int tower. The stream view ({@code String -> Stream(Char)}) and
 * combinator {@code concat} are Slice 2; the collection conservation atom
 * model stays parked at OTHER.
 */
class StringAltTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = PontifParser.parseModule(src, "t.ptf");
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
    void stringLiteral_evaluatesToStringValue() throws Exception {
        assertString("abc", run("\"abc\""));
    }

    @Test
    void emptyString_isValid() throws Exception {
        assertString("", run("\"\""));
    }

    @Test
    void escapes_resolve() throws Exception {
        assertString("\n", run("\"\\n\""));
        assertString("\t", run("\"\\t\""));
        assertString("\"", run("\"\\\"\""));
        assertString("\\", run("\"\\\\\""));
        assertString("a\nb\tc", run("\"a\\nb\\tc\""));
    }

    @Test
    void astralCodePoint_fullUnicodeNotJustBmp() throws Exception {
        // 😀 U+1F600 — a surrogate pair, carried verbatim in the content.
        assertString("hi😀", run("\"hi😀\""));
    }

    @Test
    void comparisons_lexicographicByCodePoint() throws Exception {
        assertEquals(true, run("\"abc\" == \"abc\""));
        assertEquals(false, run("\"abc\" == \"abd\""));
        assertEquals(true, run("\"abc\" != \"abd\""));
        assertEquals(true, run("\"abc\" < \"abd\""));
        assertEquals(true, run("\"abd\" > \"abc\""));
        assertEquals(true, run("\"abc\" <= \"abc\""));
        // A prefix is less than the longer string that extends it.
        assertEquals(true, run("\"ab\" < \"abc\""));
        assertEquals(false, run("\"abc\" < \"ab\""));
        // Code points are exact — ~= coincides with ==.
        assertEquals(true, run("\"abc\" ~= \"abc\""));
    }

    @Test
    void mixedStringInt_failsClosed_noTower() {
        CompileException e = assertThrows(CompileException.class,
                () -> run("\"a\" == 97"));
        assertTrue(e.getMessage().contains("not defined for (String, Int)"),
                () -> e.getMessage());
    }

    @Test
    void mixedStringChar_failsClosed_noTower() {
        CompileException e = assertThrows(CompileException.class,
                () -> run("\"a\" == 'a'"));
        assertTrue(e.getMessage().contains("not defined for (String, Char)"),
                () -> e.getMessage());
    }

    @Test
    void arithmeticOnStrings_failsClosed() {
        // `+` concatenates (slice 2); the other arithmetic ops are now rejected at
        // compile time — strings order, compare, and concatenate, nothing more.
        CompileException e = assertThrows(CompileException.class,
                () -> run("\"a\" * \"b\""));
        assertTrue(e.getMessage().contains("concatenates"), () -> e.getMessage());
    }

    @Test
    void concatenation_rendersAndJoins() throws Exception {
        // String + String, and `+` rendering an Int / Decimal operand (slice 2).
        assertString("abcd", run("\"ab\" + \"cd\""));
        assertString("n=5", run("\"n=\" + 5"));
        assertString("3 items", run("3 + \" items\""));
        assertString("n=5, d=1.5", run("""
                function describe(n:Int, d:Decimal):String -> "n=" + n + ", d=" + d
                describe(5, 1.5)
                """));
    }

    @Test
    void dispatchOverStringParam() throws Exception {
        assertEquals(true, run("""
                function isHi(s:String):Bool -> s == "hi"
                isHi("hi")
                """));
        assertEquals(false, run("""
                function isHi(s:String):Bool -> s == "hi"
                isHi("bye")
                """));
    }

    @Test
    void stringFieldInStruct_constructsAndProjects() throws Exception {
        assertString("ok", run("""
                struct Tagged(label:String, n:Int)
                function labelOf(t:Tagged):String -> t.label
                labelOf(Tagged("ok", 1))
                """));
    }

    @Test
    void truffleBackend_agreesOnComparisonAndConcatenation() {
        // The Truffle path shares the semantics: Cmp accepts strings, Add concatenates
        // them, and every OTHER operator still fails closed via the BinaryOp guard.
        //
        // This test used to assert that `"a" + "b"` failed on Truffle — written when `+`
        // over strings was read as arithmetic. It is not: `+` concatenates (strings.md
        // slice 2), which the interpreter has always done, so the assertion was pinning
        // an engine DIVERGENCE rather than a rule. `"n=" + n` is the most ordinary line
        // anyone writes with a string, and it produced a value on one engine and an error
        // on the other.
        PontifCompiler compiler = new PontifCompiler();
        PontifRunner runner = new PontifRunner();
        for (PontifRunner.Engine engine : PontifRunner.Engine.values()) {
            assertEquals("true", runner.run(
                    compiler.compile("\"abc\" < \"abd\"", "t.ptf"), engine).text(), engine::toString);
            assertEquals("\"ab\"", runner.run(
                    compiler.compile("\"a\" + \"b\"", "t.ptf"), engine).text(), engine::toString);
            // The other operand is rendered canonically, not promoted.
            assertEquals("\"n=3\"", runner.run(
                    compiler.compile("\"n=\" + 3", "t.ptf"), engine).text(), engine::toString);
        }
    }

    @Test
    void lexer_failsClosed_onMalformedLiterals() {
        assertThrows(ParseException.class, () -> run("\"abc"));     // unterminated
        assertThrows(ParseException.class, () -> run("\"\\q\""));  // unknown escape
    }
}
