package sibarum.pontif.parser;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AltLexerTest {

    private static List<AltToken> tokenize(String src) throws Exception {
        return new AltLexer(src, "t.ptf").tokenize();
    }

    /** Compact stringification for asserts: kind:text per token, separated by " ". */
    private static String shape(List<AltToken> tokens) {
        return tokens.stream()
                .filter(t -> t.kind() != AltToken.Kind.EOF)
                .map(t -> t.kind() + ":" + t.text())
                .collect(Collectors.joining(" "));
    }

    // --- atoms ---

    @Test
    void positiveAndNegativeIntegers_tokenize() throws Exception {
        assertEquals("INTEGER:42", shape(tokenize("42")));
        assertEquals("INTEGER:-7", shape(tokenize("-7")));
    }

    @Test
    void identifier_tokenizes() throws Exception {
        assertEquals("IDENT:factorial", shape(tokenize("factorial")));
        assertEquals("IDENT:Point", shape(tokenize("Point")));
        assertEquals("IDENT:_underscore", shape(tokenize("_underscore")));
        assertEquals("IDENT:has1number", shape(tokenize("has1number")));
    }

    // --- the headline test: operator chars split identifiers ---

    @Test
    void bracketedRefinement_splitsOnOperatorChars() throws Exception {
        assertEquals("LBRACKET:[ IDENT:Int OP:> INTEGER:0 RBRACKET:]",
                shape(tokenize("[Int>0]")));
        assertEquals("LBRACKET:[ IDENT:Int OP:== INTEGER:0 RBRACKET:]",
                shape(tokenize("[Int==0]")));
        assertEquals("LBRACKET:[ IDENT:Int OP:>= INTEGER:0 RBRACKET:]",
                shape(tokenize("[Int>=0]")));
    }

    @Test
    void pipe_isLogicalOrOperator() throws Exception {
        // `|` is the OR operator at every level (union of sorts, logical-or of bools).
        // See docs/alternative-syntax.ptf principle 4.
        assertEquals("LBRACKET:[ INTEGER:0 OP:| INTEGER:1 RBRACKET:]",
                shape(tokenize("[0|1]")));
        assertEquals("LBRACKET:[ INTEGER:-1 OP:| INTEGER:0 OP:| INTEGER:1 RBRACKET:]",
                shape(tokenize("[-1|0|1]")));
    }

    @Test
    void singleCharLogicalOps_areValid() throws Exception {
        // `&`, `|`, `!` are the logical operators. There is no `&&`/`||` — bitwise
        // ops do not exist in Pontif, so the logical ops get the short forms.
        assertEquals("OP:&", shape(tokenize("&")));
        assertEquals("OP:|", shape(tokenize("|")));
        assertEquals("OP:!", shape(tokenize("!")));
    }

    @Test
    void doubleAndOrPipe_lexAsTwoSeparateOps() throws Exception {
        // `&&` and `||` are no longer recognized as multi-char ops — they tokenize
        // as two single-char OPs. The parser will reject them.
        assertEquals("OP:& OP:&", shape(tokenize("&&")));
        assertEquals("OP:| OP:|", shape(tokenize("||")));
    }

    // --- operators ---

    @Test
    void multiCharOperators_lexedAsOne() throws Exception {
        assertEquals("OP:<=", shape(tokenize("<=")));
        assertEquals("OP:>=", shape(tokenize(">=")));
        assertEquals("OP:==", shape(tokenize("==")));
        assertEquals("OP:!=", shape(tokenize("!=")));
    }

    @Test
    void arrow_isItsOwnToken() throws Exception {
        assertEquals("ARROW:->", shape(tokenize("->")));
    }

    @Test
    void singleCharOps_lexAsOpToken() throws Exception {
        assertEquals("OP:+", shape(tokenize("+")));
        assertEquals("OP:*", shape(tokenize("*")));
        assertEquals("OP:<", shape(tokenize("<")));
    }

    @Test
    void minusIsAmbiguous_sometimesOpSometimesSignedInteger() throws Exception {
        // `-7` after no left-operand-y token → INTEGER
        assertEquals("INTEGER:-7", shape(tokenize("-7")));
        // `n - 7` (after an ident) → IDENT, OP, INTEGER
        assertEquals("IDENT:n OP:- INTEGER:7", shape(tokenize("n - 7")));
        // `n -7` (no space between - and 7, but ident before) — ident then op then int
        assertEquals("IDENT:n OP:- INTEGER:7", shape(tokenize("n -7")));
        // `(-7)` — the `(` isn't an operand terminator, AND no space, so -7 is a signed int
        assertEquals("LPAREN:( INTEGER:-7 RPAREN:)", shape(tokenize("(-7)")));
        // `(- 7)` (with space) — `-` and `7` are separate tokens
        assertEquals("LPAREN:( OP:- INTEGER:7 RPAREN:)", shape(tokenize("(- 7)")));
    }

    @Test
    void equals_distinctFromDoubleEquals() throws Exception {
        assertEquals("EQUALS:=", shape(tokenize("=")));
        assertEquals("OP:==", shape(tokenize("==")));
        assertEquals("OP:!=", shape(tokenize("!=")));
    }

    // --- punctuation ---

    @Test
    void allBrackets_tokenize() throws Exception {
        assertEquals("LPAREN:( RPAREN:) LBRACKET:[ RBRACKET:] LBRACE:{ RBRACE:}",
                shape(tokenize("()[]{}")));
    }

    @Test
    void dotsAndAtAndColons() throws Exception {
        assertEquals("IDENT:Point DOT:. IDENT:x", shape(tokenize("Point.x")));
        assertEquals("IDENT:p COLON:: IDENT:Int", shape(tokenize("p:Int")));
        assertEquals("AT:@ DOT:. IDENT:field", shape(tokenize("@.field")));
    }

    @Test
    void comma_tokenizes() throws Exception {
        assertEquals("IDENT:x COMMA:, IDENT:y", shape(tokenize("x, y")));
    }

    // --- whitespace + comments ---

    @Test
    void hashComments_skipToEndOfLine() throws Exception {
        assertEquals("IDENT:a IDENT:b", shape(tokenize("a # this is a comment\n b")));
        assertEquals("", shape(tokenize("# only comment")));
    }

    @Test
    void whitespaceAndNewlines_areSkipped() throws Exception {
        assertEquals("IDENT:a IDENT:b", shape(tokenize("  a\n  \t  b  ")));
    }

    @Test
    void lineAndColumnTracking_isAccurate() throws Exception {
        List<AltToken> ts = tokenize("function\n  factorial");
        AltToken func = ts.get(0);
        AltToken fact = ts.get(1);
        assertEquals(1, func.line());
        assertEquals(1, func.column());
        assertEquals(2, fact.line());
        assertEquals(3, fact.column());
    }

    // --- error cases ---

    @Test
    void unknownNonAsciiChar_throws() throws Exception {
        assertThrows(ParseException.class, () -> tokenize("é"));
    }

    // --- realistic snippet ---

    @Test
    void functionSignature_tokenizesAsExpected() throws Exception {
        // function factorial(n:[Int==0]):Int -> 1
        List<AltToken> ts = tokenize("function factorial(n:[Int==0]):Int -> 1");
        String s = shape(ts);
        assertTrue(s.contains("IDENT:function"));
        assertTrue(s.contains("IDENT:factorial"));
        assertTrue(s.contains("LBRACKET:["));
        assertTrue(s.contains("OP:=="));
        assertTrue(s.contains("ARROW:->"));
        assertTrue(s.contains("INTEGER:1"));
    }
}
