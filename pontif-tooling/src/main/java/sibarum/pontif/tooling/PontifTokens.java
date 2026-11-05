package sibarum.pontif.tooling;

import sibarum.pontif.parser.PontifParser;

import java.util.ArrayList;
import java.util.List;

/**
 * A single-pass, error-tolerant token scan of Pontif source, for colouring it while it is being typed.
 *
 * <p>Deliberately NOT built on {@code PontifLexer}: the real lexer throws on invalid input (mid-edit source
 * usually is invalid), skips comments entirely, and reports line and column rather than the flat character
 * offsets an editor colours by. The scan mirrors the lexer's token shapes tolerantly, so anything that does not
 * scan is simply left out. The keyword vocabulary is not mirrored: it is read from {@link PontifParser#KEYWORDS},
 * so a keyword added to the parser is a {@link Kind#KEYWORD} here with no second list to update.
 *
 * <p>What colour each kind wears is the editor's business. The Pontif Editor dims comments and literals and gives
 * every {@link Kind#NAME} a hue hashed from its text; another editor may do something else with the same tokens.
 * Operators, punctuation and whitespace are not tokens: they keep the editor's base colour.
 *
 * <p>Stateless and thread-safe.
 */
public final class PontifTokens {

    /** What a token is. */
    public enum Kind {
        /** {@code #} to the end of the line. */
        COMMENT,
        /** A string literal, quotes included. */
        STRING,
        /** A char literal, quotes included. */
        CHAR,
        /** An integer or decimal literal. A leading {@code -} is not part of it. */
        NUMBER,
        /** The {@code @} principal subject. */
        SUBJECT,
        /**
         * A reserved word: one of {@link PontifParser#KEYWORDS}, the receiver {@code this}, or {@code type} in
         * {@code this.type}. {@code this} is not a parser keyword, so it cannot be in that set without disturbing
         * name validation; {@code type} is one today, and {@code this.type} stays reserved if it stops being.
         */
        KEYWORD,
        /** Any other identifier: a name a program chose, or a builtin sort such as {@code Int}. */
        NAME
    }

    /** A token: its kind and its characters, {@code [start, end)}. */
    public record Token(Kind kind, int start, int end) {
    }

    private PontifTokens() {
    }

    /** The tokens of {@code content}, in order and never overlapping. Never throws. */
    public static List<Token> scan(String content) {
        List<Token> out = new ArrayList<>();
        int n = content.length();
        int i = 0;
        while (i < n) {
            char c = content.charAt(i);
            if (c == '#') {
                // Comment: to the end of the line, the lexer's skip rule.
                int start = i;
                while (i < n && content.charAt(i) != '\n') i++;
                out.add(new Token(Kind.COMMENT, start, i));
            } else if (c == '@') {
                out.add(new Token(Kind.SUBJECT, i, i + 1));
                i++;
            } else if (c == '\'') {
                int end = charLiteralEnd(content, i);
                if (end > 0) {
                    out.add(new Token(Kind.CHAR, i, end));
                    i = end;
                } else {
                    i++;    // does not scan as a char literal: left out
                }
            } else if (c == '"') {
                int end = stringLiteralEnd(content, i);
                if (end > 0) {
                    out.add(new Token(Kind.STRING, i, end));
                    i = end;
                } else {
                    i++;    // does not scan as a string literal: left out
                }
            } else if (isDigit(c)) {
                // Integer or decimal. The '.' joins only when a digit follows at once, the rule the lexer uses to
                // keep field access (x.y) out of decimal literals. A leading '-' is left out: the lexer decides
                // sign or operator by context, which a flat scan does not imitate.
                int start = i;
                while (i < n && isDigit(content.charAt(i))) i++;
                if (i + 1 < n && content.charAt(i) == '.' && isDigit(content.charAt(i + 1))) {
                    i++;
                    while (i < n && isDigit(content.charAt(i))) i++;
                }
                out.add(new Token(Kind.NUMBER, start, i));
            } else if (isIdentStart(c)) {
                int start = i;
                while (i < n && isIdentPart(content.charAt(i))) i++;
                String word = content.substring(start, i);
                out.add(new Token(isReserved(word, content, start) ? Kind.KEYWORD : Kind.NAME, start, i));
            } else {
                i++;
            }
        }
        return out;
    }

    /**
     * End index (exclusive) of a char literal opening at {@code i}, or -1 if it does not scan. Mirrors
     * {@code PontifLexer.readChar}: one escape from {@code \n \t \' \\} or one code point (surrogate pairs
     * included), then the closing quote. Where the lexer throws (empty, unknown escape, unterminated), this
     * returns -1.
     */
    public static int charLiteralEnd(String content, int i) {
        int n = content.length();
        int p = i + 1;                          // past the opening quote
        if (p >= n) return -1;
        char c = content.charAt(p);
        if (c == '\'') return -1;               // '' names no character
        if (c == '\\') {
            p++;
            if (p >= n) return -1;
            char esc = content.charAt(p);
            if (esc != 'n' && esc != 't' && esc != '\'' && esc != '\\') return -1;
            p++;
        } else {
            p += Character.charCount(content.codePointAt(p));
        }
        if (p >= n || content.charAt(p) != '\'') return -1;
        return p + 1;                           // past the closing quote
    }

    /**
     * End index (exclusive) of a string literal opening at {@code i}, or -1 if it does not scan. Mirrors
     * {@code PontifLexer.readString}: code points (surrogate pairs included) or escapes from
     * {@code \n \t \" \\}, then the closing quote. Where the lexer throws (unknown escape, unterminated), this
     * returns -1.
     */
    public static int stringLiteralEnd(String content, int i) {
        int n = content.length();
        int p = i + 1;                          // past the opening quote
        while (p < n) {
            char c = content.charAt(p);
            if (c == '"') return p + 1;         // past the closing quote
            if (c == '\\') {
                p++;
                if (p >= n) return -1;
                char esc = content.charAt(p);
                if (esc != 'n' && esc != 't' && esc != '"' && esc != '\\') return -1;
                p++;
            } else {
                p += Character.charCount(content.codePointAt(p));
            }
        }
        return -1;                              // unterminated
    }

    // ASCII predicates matching PontifLexer's identifier rules: a start is a letter or underscore; a continuation
    // adds digits and '$'.

    public static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    public static boolean isIdentPart(char c) {
        return isIdentStart(c) || isDigit(c) || c == '$';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isReserved(String word, String content, int start) {
        if (PontifParser.KEYWORDS.contains(word)) return true;
        if (word.equals("this")) return true;
        return word.equals("type") && precededByThisDot(content, start);
    }

    /** True when the identifier at {@code start} directly follows {@code this.}, as accessor syntax is written. */
    private static boolean precededByThisDot(String content, int start) {
        int dot = start - 1;
        if (dot < 0 || content.charAt(dot) != '.') return false;
        int s = dot;
        while (s > 0 && isIdentPart(content.charAt(s - 1))) s--;
        return content.substring(s, dot).equals("this");
    }
}
