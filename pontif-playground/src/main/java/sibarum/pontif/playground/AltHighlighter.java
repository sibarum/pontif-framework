package sibarum.pontif.playground;

import sibarum.dasum.gui.core.input.TextStyle;
import sibarum.dasum.gui.core.render.Color;
import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrStmt;
import sibarum.pontif.parser.AltParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Editor colorizer for Pontif alt syntax. Two independent passes:
 *
 * <p><b>Foreground</b> — a single-pass, error-tolerant token scanner:
 * comments, literals (numbers, chars, strings — dimmed neutral, never hued),
 * and the {@code @} principal subject; keywords keep the base color. User
 * names get the hue rainbow (the only semantic color). Deliberately NOT built on
 * {@link sibarum.pontif.parser.AltLexer}: the real lexer throws on invalid
 * input (mid-edit source usually is invalid), skips comments entirely, and
 * reports line/column rather than the flat char offsets {@link TextStyle}
 * wants. The scanning mirrors the lexer's token shapes tolerantly —
 * anything that doesn't scan just stays uncolored. The keyword vocabulary
 * is NOT mirrored: it is read from {@link AltParser#KEYWORDS}, so a keyword
 * added to the parser highlights here with no second list to update.
 *
 * <p><b>Background</b> — function-body tints, parser-backed: where a body
 * starts and ends (the lets through the final expression) is not decidable
 * by a flat scanner — a top-level {@code let} is textually identical to a
 * body {@code let} — so this pass runs the real {@link AltParser} and uses
 * the statement origins as boundaries. When the source doesn't parse
 * (mid-edit), body spans are CLEARED rather than left stale: no claim
 * beats a wrong claim. They reappear on the next parseable keystroke.
 */
final class AltHighlighter {

    // --- Foreground palette (dark-theme; editor default CODE_FG stays the base) ---
    // Non-name terms carry NO semantic hue — only user names get the rainbow.
    // DIM_WHITE renders a touch dimmer than the base CODE_FG (0.92, 0.94, 0.97)
    // so these terms read as distinct from the bright neutral-white terms
    // (keywords, operators) without a color.
    private static final Color DIM_WHITE = new Color(0.66f, 0.68f, 0.71f, 1f);
    /** Comments (`#…`) — dimmed neutral, no hue. */
    private static final Color COMMENT = DIM_WHITE;
    /** Literals (numbers, chars, strings) — dimmed neutral, no hue. */
    private static final Color LITERAL = DIM_WHITE;
    /** The {@code @} principal subject — dimmed neutral, no hue. */
    private static final Color SUBJECT = DIM_WHITE;

    // The body tint is neutral too: a single very dark gray div down the
    // indented block, distinct from the editor background (0.07, 0.09, 0.13)
    // but carrying no semantic color.
    private static final Color BODY_BG = new Color(0.16f, 0.16f, 0.17f, 0.55f);
    /** Paren-block tint, both brackets included; everywhere parens balance. */
    private static final Color PAREN_BG = new Color(0.30f, 0.85f, 0.40f, 0.20f);

    // Identifier rainbow: every non-keyword identifier is tinted by a hue
    // hashed from its name — same name, same color, everywhere. Saturation
    // and value are fixed (readable on the dark theme); only hue varies.
    // Builtin sort names (Int, Bool, ...) hash like everything else — no
    // exclusion list to go stale; they simply have stable colors too.
    private static final float IDENT_SATURATION = 0.55f;
    private static final float IDENT_VALUE = 0.95f;

    /** Foreground token spans + background block spans for one pass. */
    record Styles(List<TextStyle> foreground, List<TextStyle> background) {}

    private AltHighlighter() {}

    /** Style ranges for {@code content}. Never throws. */
    static Styles highlight(String content) {
        List<TextStyle> fg = new ArrayList<>();
        List<TextStyle> bg = new ArrayList<>();
        scanTokens(content, fg, bg);
        bg.addAll(bodySpans(content));
        // Backgrounds render in list order, each over the previous, so sort
        // outermost-first. Spans only nest or stay disjoint (the signature and
        // body fills are adjacent, meeting at the arrow; body spans come from
        // the parser), so (start asc, end desc) is exactly that order.
        bg.sort(java.util.Comparator
                .comparingInt(TextStyle::start)
                .thenComparing(java.util.Comparator.comparingInt(TextStyle::end).reversed()));
        return new Styles(fg, bg);
    }

    // --- Tolerant token scan: foreground colors + paren-block tints ---

    private static void scanTokens(String content, List<TextStyle> fg, List<TextStyle> bg) {
        List<Integer> openParens = new ArrayList<>();
        int n = content.length();
        int i = 0;
        while (i < n) {
            char c = content.charAt(i);
            if (c == '#') {
                // Comment — to end of line, same as AltLexer's skip rule.
                int start = i;
                while (i < n && content.charAt(i) != '\n') i++;
                fg.add(new TextStyle(start, i, COMMENT));
            } else if (c == '@') {
                fg.add(new TextStyle(i, i + 1, SUBJECT));
                i++;
            } else if (c == '(') {
                openParens.add(i);
                i++;
            } else if (c == ')') {
                // Matched pairs only; a stray close is ignored and an
                // unclosed open at EOF gets no span (tolerant mid-edit).
                // The span reaches back over the call target in front of
                // the open paren — "target(...)" tints as one block.
                // EXPERIMENT (2026-06-05): paren tints OFF while trying the
                // identifier rainbow — re-enable by restoring the add.
                if (!openParens.isEmpty()) {
                    int open = openParens.remove(openParens.size() - 1);
                    // bg.add(new TextStyle(targetStart(content, open), i + 1, PAREN_BG));
                }
                i++;
            } else if (c == '\'') {
                int end = charLiteralEnd(content, i);
                if (end > 0) {
                    fg.add(new TextStyle(i, end, LITERAL));
                    i = end;
                } else {
                    i++;    // doesn't scan as a char literal — leave uncolored
                }
            } else if (c == '"') {
                int end = stringLiteralEnd(content, i);
                if (end > 0) {
                    fg.add(new TextStyle(i, end, LITERAL));
                    i = end;
                } else {
                    i++;    // doesn't scan as a string literal — leave uncolored
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
                fg.add(new TextStyle(start, i, LITERAL));
            } else if (isIdentStart(c)) {
                int start = i;
                while (i < n && isIdentPart(content.charAt(i))) i++;
                // Keywords keep the editor's default color — structure words
                // stay quiet; only user-defined names get the hue rainbow.
                String word = content.substring(start, i);
                if (!AltParser.KEYWORDS.contains(word)) {
                    fg.add(new TextStyle(start, i, identColor(word)));
                }
            } else {
                i++;
            }
        }
    }

    // --- Background: parser-backed function-body spans ---

    // Last successfully parsed editor content and the declaration spans it
    // produced. When a keystroke breaks the parse, the cached spans are
    // carried forward shifted by the edit delta (see shiftSpans) instead of
    // vanishing — deliberately erring toward spanning too far over showing
    // nothing. Single-editor assumption; only touched from the GLFW thread
    // (content-change listener + initial publish).
    private static String lastParsedContent = null;
    private static List<TextStyle> lastDeclSpans = List.of();

    // Largest edit (changed-region length, by prefix/suffix diff) across which
    // a broken-parse keeps carrying the last good body div. Keystroke-sized;
    // anything bigger is a structural change and clears instead of smearing.
    private static final int CARRY_MAX_EDIT = 64;

    private static List<TextStyle> bodySpans(String content) {
        IrModule module;
        try {
            module = AltParser.parseModule(content, "<editor>");
        } catch (Exception e) {
            if (lastParsedContent == null) {
                return List.of();   // never had a good parse — nothing to carry
            }
            // Carry the last good spans across a SMALL edit — a keystroke that
            // briefly breaks the parse — so the body div doesn't flicker. But a
            // LARGE change (a paste, a file swap, deleting a declaration) must
            // not smear a stale div across unrelated text: clear it. Once the
            // edit is past keystroke size it is no longer cosmetic, and "no
            // claim beats a wrong claim."
            if (changedSpan(lastParsedContent, content) > CARRY_MAX_EDIT) {
                lastParsedContent = content;
                lastDeclSpans = List.of();
                return List.of();
            }
            List<TextStyle> shifted = shiftSpans(lastDeclSpans, lastParsedContent, content);
            // Re-anchor so consecutive broken keystrokes shift incrementally
            // from each other, not from an ever-staler snapshot.
            lastParsedContent = content;
            lastDeclSpans = shifted;
            return shifted;
        }

        int[] lineStarts = lineIndex(content);
        // Every top-level statement start is a boundary that caps the body
        // extent of the declaration before it. Statement origins are points
        // at the declaring keyword; synthetic decls (e.g. destructuring
        // lets) share their source statement's origin — duplicates are
        // harmless in a boundary list.
        List<Integer> declStarts = new ArrayList<>();
        List<Integer> boundaries = new ArrayList<>();
        for (IrStmt s : module.statements()) {
            Origin o = s.origin();
            if (o == null || !o.isPresent()) continue;
            int off = offsetOf(o.span().start(), lineStarts, content.length());
            boundaries.add(off);
            declStarts.add(off);
        }
        // A trailing main expression caps the last declaration's body too.
        if (module.main() != null && module.main().origin() != null
                && module.main().origin().isPresent()) {
            boundaries.add(offsetOf(module.main().origin().span().start(),
                    lineStarts, content.length()));
        }
        boundaries.sort(Integer::compare);

        List<TextStyle> bg = new ArrayList<>();
        for (int declOff : declStarts) {
            // Only true function/method declarations get a body tint. The
            // textual check filters out top-level lets, which also lower to
            // FunctionDecl but whose match-arm arrows would fool the
            // body-intro search below.
            if (!wordAt(content, declOff, "function") && !wordAt(content, declOff, "method")) {
                continue;
            }
            int bound = content.length();
            for (int b : boundaries) {
                if (b > declOff) { bound = b; break; }
            }
            // The body is a DIV down the indented block: it opens at the LAST
            // character of the declaration's first line (the `:[` or `->`
            // sitting there) and fills every following line that is INDENTED
            // (starts with a space or tab) out to the right edge. The first
            // line back at column 0 — the closing `];`, or the next
            // declaration — ends the block and stays untinted (unless it, too,
            // is indented).
            int headerNl = content.indexOf('\n', declOff);
            if (headerNl < 0 || headerNl >= bound) {
                continue;   // no following lines to wrap (file/decl tail)
            }
            int start = headerNl - 1;       // last character of the first line
            int end = -1;
            int pos = headerNl + 1;         // first char of the next line
            while (pos < bound) {
                char c = content.charAt(pos);
                if (c != ' ' && c != '\t') break;   // column 0 — block ends here
                int nl = content.indexOf('\n', pos);
                if (nl < 0 || nl >= bound) { end = bound; break; }
                end = nl + 1;               // include the '\n' so the div fills the line
                pos = nl + 1;
            }
            if (end > start) {
                // Indented multi-line body: one neutral dark-gray div from the
                // first line's last char through the last indented line.
                bg.add(new TextStyle(start, end, BODY_BG, true));
            } else {
                // No indented block — an inline body (`… -> expr` on the header
                // line): tint the arrow's right-hand side only.
                int arrowEnd = bodyIntroArrowEnd(content, declOff, headerNl);
                if (arrowEnd > 0) {
                    int s = arrowEnd - 2;
                    int e = headerNl < bound ? headerNl + 1 : headerNl;
                    if (e > s) bg.add(new TextStyle(s, e, BODY_BG, true));
                }
            }
        }
        lastParsedContent = content;
        lastDeclSpans = bg;
        return bg;
    }

    /**
     * Carry spans across one edit that broke the parse: positions are
     * remapped through a common-prefix/common-suffix diff of the two
     * texts. Spans before the edit stay put, spans after shift by the
     * length delta, and a span touching the edited region stretches to
     * cover all of it — over-spanning beats vanishing while the user is
     * mid-keystroke. Fresh truth replaces all of this on the next
     * successful parse.
     */
    /**
     * Length of the changed region between two texts, via a common-prefix /
     * common-suffix diff — the larger of the old and new changed runs. A
     * single keystroke is ~1; a paste or file swap is large. Used to decide
     * whether a broken-parse edit is small enough to carry the cached div
     * across (see {@link #CARRY_MAX_EDIT}).
     */
    private static int changedSpan(String oldC, String newC) {
        int oldLen = oldC.length();
        int newLen = newC.length();
        int max = Math.min(oldLen, newLen);
        int p = 0;
        while (p < max && oldC.charAt(p) == newC.charAt(p)) p++;
        int s = 0;
        while (s < max - p && oldC.charAt(oldLen - 1 - s) == newC.charAt(newLen - 1 - s)) s++;
        return Math.max((oldLen - s) - p, (newLen - s) - p);
    }

    private static List<TextStyle> shiftSpans(List<TextStyle> spans, String oldC, String newC) {
        int oldLen = oldC.length();
        int newLen = newC.length();
        int max = Math.min(oldLen, newLen);
        int p = 0;
        while (p < max && oldC.charAt(p) == newC.charAt(p)) p++;
        int s = 0;
        while (s < max - p && oldC.charAt(oldLen - 1 - s) == newC.charAt(newLen - 1 - s)) s++;
        int delta = newLen - oldLen;
        int oldEditEnd = oldLen - s;    // edit region: old [p, oldEditEnd) → new [p, newLen - s)

        List<TextStyle> out = new ArrayList<>(spans.size());
        for (TextStyle t : spans) {
            int start = t.start() <= p ? t.start()
                    : t.start() >= oldEditEnd ? t.start() + delta
                    : p;                          // started inside the edit — snap to its left edge
            int end = t.end() <= p ? t.end()
                    : t.end() >= oldEditEnd ? t.end() + delta
                    : newLen - s;                 // ended inside the edit — swallow the whole region
            if (end > start) out.add(new TextStyle(start, end, t.color(), t.wrapLineEndings()));
        }
        return out;
    }

    /**
     * End offset (exclusive) of the body-intro {@code ->} between
     * {@code from} and {@code bound}, or -1 if there is none. Comments and
     * char literals are skipped, so a {@code ->} in a doc line can't fake a
     * body. The first live arrow after a function/method head IS the body
     * intro — params and return sorts contain no arrows.
     */
    private static int bodyIntroArrowEnd(String content, int from, int bound) {
        int i = from;
        while (i < bound - 1) {
            char c = content.charAt(i);
            if (c == '#') {
                while (i < bound && content.charAt(i) != '\n') i++;
            } else if (c == '\'') {
                int end = charLiteralEnd(content, i);
                i = end > 0 ? end : i + 1;
            } else if (c == '-' && content.charAt(i + 1) == '>') {
                return i + 2;
            } else {
                i++;
            }
        }
        return -1;
    }

    /**
     * Stable per-name color: the hue is hashed from the identifier text
     * (Fibonacci-scrambled — String.hashCode alone clusters short names),
     * saturation and value fixed. Same name → same color, every occurrence,
     * every session.
     */
    private static Color identColor(String name) {
        int h = name.hashCode() * 0x9E3779B9;
        float hue = ((h >>> 8) & 0xFFFFFF) / (float) 0x1000000 * 360f;
        return hsv(hue, IDENT_SATURATION, IDENT_VALUE);
    }

    /** HSV → RGB at alpha 1. Hue in degrees [0, 360). */
    private static Color hsv(float hue, float s, float v) {
        float c = v * s;
        float x = c * (1f - Math.abs((hue / 60f) % 2f - 1f));
        float m = v - c;
        float r, g, b;
        if      (hue <  60f) { r = c; g = x; b = 0; }
        else if (hue < 120f) { r = x; g = c; b = 0; }
        else if (hue < 180f) { r = 0; g = c; b = x; }
        else if (hue < 240f) { r = 0; g = x; b = c; }
        else if (hue < 300f) { r = x; g = 0; b = c; }
        else                 { r = c; g = 0; b = x; }
        return new Color(r + m, g + m, b + m, 1f);
    }

    /**
     * Start of the call target immediately in front of an open paren —
     * the identifier chain ({@code foo}, {@code Type.method}) ending at
     * {@code openParen} with no gap. Grouping parens with no target
     * ({@code x * (x - 1)}) get back {@code openParen} unchanged; a
     * chained call ({@code f(x).g(y)}) reaches back only to {@code g}.
     */
    private static int targetStart(String content, int openParen) {
        int i = openParen;
        while (i > 0 && (isIdentPart(content.charAt(i - 1)) || content.charAt(i - 1) == '.')) {
            i--;
        }
        while (i < openParen && content.charAt(i) == '.') i++;   // ".g(" → "g("
        return i;
    }

    /** Start offsets of each line, for 1-indexed (line, column) → flat offset. */
    private static int[] lineIndex(String content) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') starts.add(i + 1);
        }
        int[] out = new int[starts.size()];
        for (int i = 0; i < out.length; i++) out[i] = starts.get(i);
        return out;
    }

    private static int offsetOf(Origin.Position p, int[] lineStarts, int length) {
        if (p.line() - 1 >= lineStarts.length) return length;
        return Math.min(length, lineStarts[p.line() - 1] + p.column() - 1);
    }

    /** True when {@code word} sits at {@code off} with an identifier boundary after it. */
    private static boolean wordAt(String content, int off, String word) {
        if (!content.startsWith(word, off)) return false;
        int after = off + word.length();
        return after >= content.length() || !isIdentPart(content.charAt(after));
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

    /**
     * End index (exclusive) of a string literal opening at {@code i}, or -1
     * if it doesn't scan. Mirrors {@code AltLexer.readString}: zero or more
     * code points (surrogate pairs included) or escapes from
     * {@code \n \t \" \\}, then the mandatory closing quote. Where the lexer
     * throws (unknown escape, unterminated), this returns -1.
     */
    private static int stringLiteralEnd(String content, int i) {
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

    // ASCII predicates matching AltLexer's identifier rules: start is a
    // letter or underscore; continue adds digits and '$'.
    private static boolean isDigit(char c)      { return c >= '0' && c <= '9'; }
    private static boolean isIdentStart(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'; }
    private static boolean isIdentPart(char c)  { return isIdentStart(c) || isDigit(c) || c == '$'; }
}
