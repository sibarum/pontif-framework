package sibarum.pontif.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexerTest {

    private static List<Token> tokenize(String src) throws ParseException {
        return new Lexer(src, "t.ptf").tokenize();
    }

    @Test
    void emptyInput_yieldsOnlyEof() throws Exception {
        List<Token> ts = tokenize("");
        assertEquals(1, ts.size());
        assertEquals(Token.Kind.EOF, ts.get(0).kind());
    }

    @Test
    void parens_tokenize() throws Exception {
        List<Token> ts = tokenize("()");
        assertEquals(Token.Kind.LPAREN, ts.get(0).kind());
        assertEquals(Token.Kind.RPAREN, ts.get(1).kind());
        assertEquals(Token.Kind.EOF, ts.get(2).kind());
    }

    @Test
    void positiveAndNegativeIntegers_tokenize() throws Exception {
        List<Token> ts = tokenize("42 -7");
        assertEquals(Token.Kind.INTEGER, ts.get(0).kind());
        assertEquals("42", ts.get(0).text());
        assertEquals(Token.Kind.INTEGER, ts.get(1).kind());
        assertEquals("-7", ts.get(1).text());
    }

    @Test
    void bareMinusIsASymbol_notAnInteger() throws Exception {
        List<Token> ts = tokenize("- 3");
        assertEquals(Token.Kind.SYMBOL, ts.get(0).kind());
        assertEquals("-", ts.get(0).text());
        assertEquals(Token.Kind.INTEGER, ts.get(1).kind());
        assertEquals("3", ts.get(1).text());
    }

    @Test
    void symbols_includeAlphanumericAndOperatorChars() throws Exception {
        List<Token> ts = tokenize("hello + <= is-prime self");
        assertEquals("hello", ts.get(0).text());
        assertEquals("+", ts.get(1).text());
        assertEquals("<=", ts.get(2).text());
        assertEquals("is-prime", ts.get(3).text());
        assertEquals("self", ts.get(4).text());
    }

    @Test
    void whitespaceAndNewlines_areSkipped() throws Exception {
        List<Token> ts = tokenize("  (  a\n  b  )  ");
        assertEquals(Token.Kind.LPAREN, ts.get(0).kind());
        assertEquals("a", ts.get(1).text());
        assertEquals("b", ts.get(2).text());
        assertEquals(Token.Kind.RPAREN, ts.get(3).kind());
    }

    @Test
    void semicolonCommentsAreSkipped() throws Exception {
        List<Token> ts = tokenize("a ; this is a comment\n b");
        assertEquals("a", ts.get(0).text());
        assertEquals("b", ts.get(1).text());
        assertEquals(Token.Kind.EOF, ts.get(2).kind());
    }

    @Test
    void lineAndColumnTrackingAreAccurate() throws Exception {
        List<Token> ts = tokenize("(\n  hello)");
        Token lparen = ts.get(0);
        assertEquals(1, lparen.line());
        assertEquals(1, lparen.column());
        Token hello = ts.get(1);
        assertEquals(2, hello.line());
        assertEquals(3, hello.column());
        Token rparen = ts.get(2);
        assertEquals(2, rparen.line());
        assertEquals(8, rparen.column());
    }

    @Test
    void compoundExpression_yieldsExpectedTokenStream() throws Exception {
        List<Token> ts = tokenize("(+ 1 (* 2 3))");
        assertEquals(10, ts.size());
        assertEquals(Token.Kind.LPAREN, ts.get(0).kind());
        assertEquals("+", ts.get(1).text());
        assertEquals("1", ts.get(2).text());
        assertEquals(Token.Kind.LPAREN, ts.get(3).kind());
        assertEquals("*", ts.get(4).text());
        assertEquals("2", ts.get(5).text());
        assertEquals("3", ts.get(6).text());
        assertEquals(Token.Kind.RPAREN, ts.get(7).kind());
        assertEquals(Token.Kind.RPAREN, ts.get(8).kind());
        assertEquals(Token.Kind.EOF, ts.get(9).kind());
    }

    @Test
    void unsupportedCharacter_throwsWithOrigin() throws Exception {
        // Non-ASCII character outside the printable ASCII range we accept as symbol chars.
        ParseException ex = assertThrows(ParseException.class,
                () -> tokenize("(hello é)"));
        assertTrue(ex.getMessage().contains("t.ptf"),
                "error should include source name; got: " + ex.getMessage());
    }
}
