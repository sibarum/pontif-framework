package sibarum.pontif.playground;

import sibarum.dasum.gui.core.input.TextStyle;
import sibarum.dasum.gui.core.render.Color;
import sibarum.pontif.parser.AltParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Single-pass, error-tolerant colorizer for Pontif alt syntax — comments,
 * keywords, literals (numbers and chars), and the {@code @} principal
 * subject. Everything else keeps the editor's default color.
 *
 * <p>Deliberately NOT built on {@link sibarum.pontif.parser.AltLexer}: the
 * real lexer throws on invalid input (mid-edit source usually is invalid),
 * skips comments entirely, and reports line/column rather than the flat
 * char offsets {@link TextStyle} wants. The scanning here mirrors the
 * lexer's token shapes tolerantly — anything that doesn't scan just stays
 * uncolored. The keyword vocabulary is NOT mirrored: it is read from
 * {@link AltParser#KEYWORDS}, so a keyword added to the parser highlights
 * here with no second list to update.
 */
final class AltHighlighter {

    // --- Palette (dark-theme; editor default CODE_FG stays the base) ---
    private static final Color COMMENT = new Color(0.45f, 0.60f, 0.45f, 1f);
    private static final Color KEYWORD = new Color(0.55f, 0.65f, 0.95f, 1f);
    private static final Color LITERAL = new Color(0.95f, 0.75f, 0.45f, 1f);
    private static final Color SUBJECT = new Color(0.40f, 0.85f, 0.90f, 1f);

    private AltHighlighter() {}

    /** Foreground style ranges for {@code content}. Never throws. */
    static List<TextStyle> highlight(String content) {
        List<TextStyle> out = new ArrayList<>();
        int n = content.length();
        int i = 0;
        while (i < n) {
            char c = content.charAt(i);
            if (c == '#') {
                // Comment — to end of line, same as AltLexer's skip rule.
                int start = i;
                while (i < n && content.charAt(i) != '\n') i++;
                out.add(new TextStyle(start, i, COMMENT));
            } else if (c == '@') {
                out.add(new TextStyle(i, i + 1, SUBJECT));
                i++;
            } else if (c == '\'') {
                int end = charLiteralEnd(content, i);
                if (end > 0) {
                    out.add(new TextStyle(i, end, LITERAL));
                    i = end;
                } else {
                    i++;    // doesn't scan as a char literal — leave uncolored
                }
            } else if (isDigit(c)) {
                // Integer / decimal. The '.' joins only when a digit follows
                // immediately — same rule the lexer uses to keep field access
                // (x.y) out of decimal literals. Leading '-' stays uncolored;
                // the lexer treats it as sign-vs-operator by context, which a
                // basic pass doesn't imitate.
                int start = i;
                while (i < n && isDigit(content.charAt(i))) i++;
                if (i + 1 < n && content.charAt(i) == '.' && isDigit(content.charAt(i + 1))) {
                    i++;
                    while (i < n && isDigit(content.charAt(i))) i++;
                }
                out.add(new TextStyle(start, i, LITERAL));
            } else if (isIdentStart(c)) {
                int start = i;
                while (i < n && isIdentPart(content.charAt(i))) i++;
                if (AltParser.KEYWORDS.contains(content.substring(start, i))) {
                    out.add(new TextStyle(start, i, KEYWORD));
                }
            } else {
                i++;
            }
        }
        return out;
    }

    /**
     * End index (exclusive) of a char literal opening at {@code i}, or -1
     * if it doesn't scan. Mirrors {@code AltLexer.readChar}: one escape
     * from {@code \n \t \' \\} or one code point (surrogate pairs
     * included), then the mandatory closing quote. Where the lexer throws
     * (empty, unknown escape, unterminated), this returns -1.
     */
    private static int charLiteralEnd(String content, int i) {
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

    // ASCII predicates matching AltLexer's identifier rules: start is a
    // letter or underscore; continue adds digits and '$'.
    private static boolean isDigit(char c)      { return c >= '0' && c <= '9'; }
    private static boolean isIdentStart(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'; }
    private static boolean isIdentPart(char c)  { return isIdentStart(c) || isDigit(c) || c == '$'; }
}
