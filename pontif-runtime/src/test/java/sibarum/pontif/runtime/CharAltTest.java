package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.types.CharValue;
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
 * End-to-end coverage for the {@code Char} value slice (the fourth scalar):
 * single-quoted literals (full Unicode, escapes {@code \n \t \' \\}),
 * code-point ordering and equality, dispatch over {@code Char}-typed params,
 * and the fail-closed fences — no arithmetic on chars, no Char/Int tower.
 * Narrows over {@code Char} are the follow-up slice (Char is discrete, so
 * they may legitimately route through integer discharge — unlike Decimal).
 */
class CharAltTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = AltParser.parseModule(src, "t.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    private void assertChar(int codePoint, Object actual) {
        assertTrue(actual instanceof CharValue,
                () -> "Expected a CharValue, got " + (actual == null ? "null" : actual.getClass().getSimpleName()));
        assertEquals(codePoint, ((CharValue) actual).codePoint());
    }

    @Test
    void charLiteral_evaluatesToCharValue() throws Exception {
        assertChar('a', run("'a'"));
    }

    @Test
    void escapes_resolve() throws Exception {
        assertChar('\n', run("'\\n'"));
        assertChar('\t', run("'\\t'"));
        assertChar('\'', run("'\\''"));
        assertChar('\\', run("'\\\\'"));
    }

    @Test
    void astralCodePoint_fullUnicodeNotJustBmp() throws Exception {
        // 😀 U+1F600 — a surrogate pair in the source text, ONE code point in
        // the value.
        assertChar(0x1F600, run("'😀'"));
    }

    @Test
    void comparisons_byCodePoint() throws Exception {
        assertEquals(true, run("'a' == 'a'"));
        assertEquals(false, run("'a' == 'b'"));
        assertEquals(true, run("'a' != 'b'"));
        assertEquals(true, run("'a' < 'b'"));
        assertEquals(true, run("'z' > 'a'"));
        assertEquals(true, run("'a' <= 'a'"));
        // Code points are exact values — ~= coincides with ==.
        assertEquals(true, run("'a' ~= 'a'"));
    }

    @Test
    void mixedCharInt_failsClosed_noTower() {
        // No Char/Int tower: 'a' is not 97 until an ord/chr pair is ruled — and
        // the undefined operator is now rejected at compile time, not at runtime.
        CompileException e = assertThrows(CompileException.class,
                () -> run("'a' == 97"));
        assertTrue(e.getMessage().contains("not defined for (Char, Int)"),
                () -> e.getMessage());
    }

    @Test
    void arithmeticOnChars_failsClosed() {
        CompileException e = assertThrows(CompileException.class,
                () -> run("'a' + 'b'"));
        assertTrue(e.getMessage().contains("don't compute"), () -> e.getMessage());
    }

    @Test
    void dispatchOverCharParam() throws Exception {
        assertEquals(true, run("""
                function isA(c:Char):Bool -> c == 'a'
                isA('a')
                """));
        assertEquals(false, run("""
                function isA(c:Char):Bool -> c == 'a'
                isA('b')
                """));
    }

    @Test
    void charFieldInStruct_constructsAndProjects() throws Exception {
        assertChar('x', run("""
                struct Key(tag:Char, n:Int)
                function tagOf(k:Key):Char -> k.tag
                tagOf(Key('x', 1))
                """));
    }

    @Test
    void charLiteralFieldPattern_pinsByCodePoint() throws Exception {
        // The literal field pattern desugars to [Char:@=='a'] — decided by
        // the CMP_CHR_CHR fold after Self-substitution.
        assertEquals(1L, run("""
                struct Key(tag:Char)
                function grade(k:Key):Int -> match k {
                  [Key('a')] -> 1
                  _ -> 0
                }
                grade(Key('a'))
                """));
        assertEquals(0L, run("""
                struct Key(tag:Char)
                function grade(k:Key):Int -> match k {
                  [Key('a')] -> 1
                  _ -> 0
                }
                grade(Key('b'))
                """));
    }

    @Test
    void truffleBackend_agreesOnComparisonAndGuard() {
        // The Truffle path shares the semantics: Cmp accepts chars, the
        // arithmetic nodes fail closed via the BinaryOp guard.
        PontifCompiler compiler = new PontifCompiler();
        PontifRunner runner = new PontifRunner();
        PontifRunner.RunResult eq = runner.run(
                compiler.compileAlt("'a' < 'b'", "t.ptf"), PontifRunner.Engine.TRUFFLE);
        assertEquals("true", eq.text());
        PontifRunner.RunResult bad = runner.run(
                compiler.compileAlt("'a' + 'b'", "t.ptf"), PontifRunner.Engine.TRUFFLE);
        assertTrue(bad.isError(), "char arithmetic must fail closed on Truffle too");
        assertTrue(bad.text().contains("don't compute"), () -> bad.text());
    }

    @Test
    void lexer_failsClosed_onMalformedLiterals() {
        assertThrows(ParseException.class, () -> run("''"));
        assertThrows(ParseException.class, () -> run("'ab'"));
        assertThrows(ParseException.class, () -> run("'\\q'"));
        assertThrows(ParseException.class, () -> run("'a"));
    }
}
