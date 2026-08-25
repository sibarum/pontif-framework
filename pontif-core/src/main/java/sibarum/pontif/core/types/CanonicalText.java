package sibarum.pontif.core.types;

import java.math.BigDecimal;

/**
 * The canonical string rendering of a built-in value — the one rule behind String
 * {@code +} concatenation and the {@code (String:value)} cast.
 *
 * <p>It lives in {@code core} because BOTH engines need it and neither can see the other:
 * the interpreter renders in {@code pontif-ir}, Truffle's {@code Add} node renders in
 * {@code pontif-ast}. A second copy would be a divergence waiting to happen, which is
 * precisely the bug that put this class here — Truffle's {@code Add} had no String branch
 * at all, so {@code "n=" + 3} produced {@code "n=3"} on the interpreter and failed closed
 * on Truffle with "strings order and compare; they don't compute".
 *
 * <p>Returns {@code null} for a value with no canonical render (an aggregate, a closure)
 * so each caller fails closed with its own message rather than inventing text for it —
 * fabricate-never.
 */
public final class CanonicalText {

    private CanonicalText() {}

    /**
     * The canonical text of {@code v}: a String verbatim, an Int/Decimal/Char/Bool as it
     * displays. Decimal uses plain (non-scientific) notation, matching its literal form.
     * {@code null} when the value has no canonical render.
     */
    public static String of(Object v) {
        if (v instanceof StringValue s) return s.content();
        if (v instanceof Long n) return Long.toString(n);
        if (v instanceof BigDecimal d) return d.toPlainString();
        if (v instanceof CharValue c) return new String(Character.toChars(c.codePoint()));
        if (v instanceof Boolean b) return b.toString();
        return null;
    }
}
