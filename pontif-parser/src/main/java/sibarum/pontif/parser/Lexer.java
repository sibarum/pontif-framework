package sibarum.pontif.parser;

import sibarum.pontif.core.Origin;

import java.util.ArrayList;
import java.util.List;

public final class Lexer {

    private final String src;
    private final String source;
    private int pos;
    private int line = 1;
    private int column = 1;

    public Lexer(String src, String source) {
        if (src == null) {
            throw new IllegalArgumentException("Source string must be non-null");
        }
        this.src = src;
        this.source = source == null ? "<input>" : source;
    }

    public List<Token> tokenize() throws ParseException {
        List<Token> tokens = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments();
            if (pos >= src.length()) {
                tokens.add(new Token(Token.Kind.EOF, "", source, line, column));
                return tokens;
            }
            char c = src.charAt(pos);
            int startLine = line;
            int startCol = column;
            if (c == '(') {
                advance();
                tokens.add(new Token(Token.Kind.LPAREN, "(", source, startLine, startCol));
            } else if (c == ')') {
                advance();
                tokens.add(new Token(Token.Kind.RPAREN, ")", source, startLine, startCol));
            } else if (isDigit(c) || (c == '-' && pos + 1 < src.length() && isDigit(src.charAt(pos + 1)))) {
                tokens.add(readInteger(startLine, startCol));
            } else if (isSymbolStart(c)) {
                tokens.add(readSymbol(startLine, startCol));
            } else {
                throw new ParseException(
                        "Unexpected character: '" + c + "' (codepoint " + (int) c + ")",
                        Origin.at(source, startLine, startCol));
            }
        }
    }

    private void skipWhitespaceAndComments() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r') {
                advance();
            } else if (c == '\n') {
                pos++;
                line++;
                column = 1;
            } else if (c == ';') {
                // Comment to end of line.
                while (pos < src.length() && src.charAt(pos) != '\n') {
                    advance();
                }
            } else {
                return;
            }
        }
    }

    private Token readInteger(int startLine, int startCol) {
        int start = pos;
        if (src.charAt(pos) == '-') {
            advance();
        }
        while (pos < src.length() && isDigit(src.charAt(pos))) {
            advance();
        }
        String text = src.substring(start, pos);
        return new Token(Token.Kind.INTEGER, text, source, startLine, startCol);
    }

    private Token readSymbol(int startLine, int startCol) {
        int start = pos;
        while (pos < src.length() && isSymbolPart(src.charAt(pos))) {
            advance();
        }
        String text = src.substring(start, pos);
        return new Token(Token.Kind.SYMBOL, text, source, startLine, startCol);
    }

    private void advance() {
        pos++;
        column++;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isSymbolStart(char c) {
        return isSymbolPart(c);
    }

    private static boolean isSymbolPart(char c) {
        if (c == '(' || c == ')' || c == ';') return false;
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') return false;
        return c >= '!' && c <= '~';
    }
}
