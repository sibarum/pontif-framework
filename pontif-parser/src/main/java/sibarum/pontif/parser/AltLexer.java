package sibarum.pontif.parser;

import sibarum.pontif.core.Origin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tokenizer for the alt syntax. Skips whitespace and {@code #}-to-end-of-line
 * comments, tokenizes operator characters as their own tokens (so {@code Int>0}
 * splits into {@code Int}, {@code >}, {@code 0}), and recognizes multi-char
 * operators ({@code ->}, {@code <=}, {@code >=}, {@code ==}, {@code !=}) greedily.
 *
 * <p>Logical operators are single-character — {@code &} for and, {@code |} for
 * or, {@code !} for not. There are no {@code &&} or {@code ||} (see
 * {@code docs/alternative-syntax.ptf} principle 4: bitwise ops do not exist
 * in Pontif, so the logical ops get the short forms).
 *
 * <p>Identifier characters: letters, digits, underscore, dollar. Identifier
 * must start with a letter or underscore. Keywords are not recognized here —
 * they're just identifiers; the parser decides by text.
 */
public final class AltLexer {

    private static final Set<String> MULTI_CHAR_OPS = Set.of(
            "<=", ">=", "==", "!=");

    /** Single-char operators that may also begin a multi-char op. */
    private static final Set<Character> OP_START_CHARS = Set.of(
            '+', '-', '*', '/', '<', '>', '=', '!', '&', '|');

    private final String src;
    private final String source;
    private int pos;
    private int line = 1;
    private int column = 1;

    public AltLexer(String src, String source) {
        if (src == null) {
            throw new IllegalArgumentException("Source string must be non-null");
        }
        this.src = src;
        this.source = source == null ? "<input>" : source;
    }

    public List<AltToken> tokenize() throws ParseException {
        List<AltToken> tokens = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments();
            if (pos >= src.length()) {
                tokens.add(new AltToken(AltToken.Kind.EOF, "", source, line, column));
                return tokens;
            }
            int startLine = line;
            int startCol = column;
            char c = src.charAt(pos);

            // Single-char punctuation (no overlap with operators)
            switch (c) {
                case '(' -> { advance(); tokens.add(new AltToken(AltToken.Kind.LPAREN, "(", source, startLine, startCol)); continue; }
                case ')' -> { advance(); tokens.add(new AltToken(AltToken.Kind.RPAREN, ")", source, startLine, startCol)); continue; }
                case '[' -> { advance(); tokens.add(new AltToken(AltToken.Kind.LBRACKET, "[", source, startLine, startCol)); continue; }
                case ']' -> { advance(); tokens.add(new AltToken(AltToken.Kind.RBRACKET, "]", source, startLine, startCol)); continue; }
                case '{' -> { advance(); tokens.add(new AltToken(AltToken.Kind.LBRACE, "{", source, startLine, startCol)); continue; }
                case '}' -> { advance(); tokens.add(new AltToken(AltToken.Kind.RBRACE, "}", source, startLine, startCol)); continue; }
                case ',' -> { advance(); tokens.add(new AltToken(AltToken.Kind.COMMA, ",", source, startLine, startCol)); continue; }
                case ':' -> { advance(); tokens.add(new AltToken(AltToken.Kind.COLON, ":", source, startLine, startCol)); continue; }
                case '.' -> { advance(); tokens.add(new AltToken(AltToken.Kind.DOT, ".", source, startLine, startCol)); continue; }
                case '@' -> { advance(); tokens.add(new AltToken(AltToken.Kind.AT, "@", source, startLine, startCol)); continue; }
                default -> { /* fall through */ }
            }

            // Integer literal — including negative when - is followed by a digit and
            // wasn't preceded by an operand (no easy way to know that here without
            // parser context; we accept -3 as INTEGER only when - is at lexer start
            // of a new token AND followed by digit AND the previous token wasn't
            // something that could be a left operand).
            if (Character.isDigit(c)) {
                tokens.add(readInteger(startLine, startCol));
                continue;
            }
            if (c == '-' && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1))
                    && !isOperandTerminator(tokens)) {
                tokens.add(readInteger(startLine, startCol));
                continue;
            }

            // Operator (including '-' when not a sign, '=', '->')
            if (OP_START_CHARS.contains(c)) {
                tokens.add(readOperator(startLine, startCol));
                continue;
            }

            // Identifier
            if (isIdentStart(c)) {
                tokens.add(readIdent(startLine, startCol));
                continue;
            }

            throw new ParseException(
                    "Unexpected character: '" + c + "' (codepoint " + (int) c + ")",
                    Origin.at(source, startLine, startCol));
        }
    }

    /**
     * Returns true if the previous emitted token could be the left operand of
     * a binary operator — meaning the next '-' is the operator, not a sign.
     */
    private static boolean isOperandTerminator(List<AltToken> tokens) {
        if (tokens.isEmpty()) return false;
        AltToken last = tokens.get(tokens.size() - 1);
        return switch (last.kind()) {
            case INTEGER, IDENT, RPAREN, RBRACKET, RBRACE, AT -> true;
            default -> false;
        };
    }

    private AltToken readInteger(int startLine, int startCol) {
        int start = pos;
        if (src.charAt(pos) == '-') {
            advance();
        }
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
            advance();
        }
        return new AltToken(AltToken.Kind.INTEGER, src.substring(start, pos), source, startLine, startCol);
    }

    private AltToken readIdent(int startLine, int startCol) {
        int start = pos;
        while (pos < src.length() && isIdentPart(src.charAt(pos))) {
            advance();
        }
        return new AltToken(AltToken.Kind.IDENT, src.substring(start, pos), source, startLine, startCol);
    }

    private AltToken readOperator(int startLine, int startCol) throws ParseException {
        // Try multi-char first (greedy 2-char lookahead).
        if (pos + 1 < src.length()) {
            String two = src.substring(pos, pos + 2);
            if (two.equals("->")) {
                advance(); advance();
                return new AltToken(AltToken.Kind.ARROW, "->", source, startLine, startCol);
            }
            if (MULTI_CHAR_OPS.contains(two)) {
                advance(); advance();
                return new AltToken(AltToken.Kind.OP, two, source, startLine, startCol);
            }
        }
        // Single-char fallbacks
        char c = src.charAt(pos);
        switch (c) {
            case '=' -> {
                advance();
                return new AltToken(AltToken.Kind.EQUALS, "=", source, startLine, startCol);
            }
            case '+', '-', '*', '/', '<', '>', '!', '&', '|' -> {
                advance();
                return new AltToken(AltToken.Kind.OP, String.valueOf(c), source, startLine, startCol);
            }
            default -> throw new ParseException(
                    "Internal: readOperator called on non-operator '" + c + "'",
                    Origin.at(source, startLine, startCol));
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
            } else if (c == '#') {
                while (pos < src.length() && src.charAt(pos) != '\n') {
                    advance();
                }
            } else {
                return;
            }
        }
    }

    private void advance() {
        pos++;
        column++;
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9') || c == '$';
    }
}
