package sibarum.pontif.gui;

import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.types.StringValue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A small recursive-descent parser: a typed math string → an {@code AlgExpr} AST (the same node
 * shapes {@code astOf} reflects and the plotter/typesetter consume). The arithmetic subset — numbers,
 * variables, {@code + - * / ^}, parentheses, unary minus, and implicit multiplication ({@code 2x},
 * {@code (x+1)(x-1)}) — which covers the polynomials and rationals the reliable plot handles. Returns
 * {@link Optional#empty()} on any parse error (unexpected or trailing input), so a live input field
 * can keep the last valid plot while the user is mid-typing.
 *
 * <p>Grammar (precedence low→high): {@code expr = term (('+'|'-') term)*}; {@code term = unary
 * (('*'|'/') unary | <implicit-mult> unary)*}; {@code unary = '-' unary | power}; {@code power = atom
 * ('^' unary)?} (right-assoc); {@code atom = number | ident | '(' expr ')'}. Each ASCII letter is its
 * own variable, so {@code xy} is {@code x·y} — no reserved names in this subset.
 */
public final class ExprParser {

    private static final String MODULE = "pontif.algebra";

    private final String src;
    private int pos;

    private ExprParser(String src) { this.src = src; }

    /** Parse {@code s} to an {@code AlgExpr} RecordValue, or empty if it isn't a valid expression. */
    public static Optional<RecordValue> parse(String s) {
        if (s == null) return Optional.empty();
        ExprParser p = new ExprParser(s);
        try {
            RecordValue e = p.expr();
            p.skipWs();
            if (p.pos != p.src.length()) return Optional.empty();   // trailing junk
            return Optional.of(e);
        } catch (ParseError err) {
            return Optional.empty();
        }
    }

    private static final class ParseError extends RuntimeException {
        ParseError() { super(null, null, false, false); }
    }

    // --- grammar ---------------------------------------------------------------------------------

    private RecordValue expr() {
        RecordValue left = term();
        while (true) {
            char c = peek();
            if (c == '+') { pos++; left = bin("Add", left, term()); }
            else if (c == '-') { pos++; left = bin("Sub", left, term()); }
            else return left;
        }
    }

    private RecordValue term() {
        RecordValue left = unary();
        while (true) {
            char c = peek();
            if (c == '*') { pos++; left = bin("Mul", left, unary()); }
            else if (c == '/') { pos++; left = bin("Div", left, unary()); }
            else if (startsAtom(c)) { left = bin("Mul", left, unary()); }   // implicit multiplication
            else return left;
        }
    }

    private RecordValue unary() {
        if (peek() == '-') {
            pos++;
            return negate(unary());
        }
        if (peek() == '+') { pos++; return unary(); }
        return power();
    }

    private RecordValue power() {
        RecordValue base = atom();
        if (peek() == '^') {
            pos++;
            return bin2("Pow", "base", base, "exponent", unary());   // right-assoc via unary→power
        }
        return base;
    }

    private RecordValue atom() {
        char c = peek();
        if (c == '(') {
            pos++;
            RecordValue e = expr();
            expect(')');
            return e;
        }
        if (isDigit(c) || c == '.') return number();
        if (isLetter(c)) return param();
        throw new ParseError();
    }

    private RecordValue number() {
        skipWs();
        int start = pos;
        // Read raw chars here — peek() would skip whitespace and pull it into the literal.
        while (pos < src.length() && isDigit(src.charAt(pos))) pos++;
        // Consume the fractional part only if the '.' is actually followed by a digit — so "2."
        // (trailing dot) and "2..3" (double dot) are rejected, while "2.5" and ".5" are accepted.
        if (pos < src.length() && src.charAt(pos) == '.'
                && pos + 1 < src.length() && isDigit(src.charAt(pos + 1))) {
            pos++;
            while (pos < src.length() && isDigit(src.charAt(pos))) pos++;
        }
        if (pos == start) throw new ParseError();
        return constNode(new BigDecimal(src.substring(start, pos)));
    }

    /** Each letter is its own single-character variable (so {@code xy} is a product). */
    private RecordValue param() {
        char c = src.charAt(pos++);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", new StringValue(String.valueOf(c)));
        return new RecordValue(MODULE + "/Param", m);
    }

    // --- node builders ---------------------------------------------------------------------------

    private RecordValue bin(String type, RecordValue l, RecordValue r) {
        return bin2(type, "left", l, "right", r);
    }

    private RecordValue bin2(String type, String an, RecordValue a, String bn, RecordValue b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(an, a);
        m.put(bn, b);
        return new RecordValue(MODULE + "/" + type, m);
    }

    private RecordValue constNode(BigDecimal v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", v);
        return new RecordValue(MODULE + "/Const", m);
    }

    /** Negation: fold into a numeric literal (a clean {@code -5}); otherwise {@code 0 - x}. */
    private RecordValue negate(RecordValue e) {
        if ((MODULE + "/Const").equals(e.typeName()) && e.members().get("value") instanceof BigDecimal v) {
            return constNode(v.negate());
        }
        return bin("Sub", constNode(BigDecimal.ZERO), e);
    }

    // --- lexing helpers --------------------------------------------------------------------------

    private char peek() { skipWs(); return pos < src.length() ? src.charAt(pos) : '\0'; }

    private void expect(char c) { if (peek() != c) throw new ParseError(); pos++; }

    private void skipWs() { while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++; }

    private static boolean startsAtom(char c) { return c == '(' || isDigit(c) || c == '.' || isLetter(c); }
    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    private static boolean isLetter(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }
}
