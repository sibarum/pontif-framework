package sibarum.pontif.core.types;

/**
 * Runtime character value — a Unicode code point (full range, not just the
 * BMP). The fourth scalar's value representation, shared by the symbolic
 * layer ({@code Force}), the interpreter, and the Truffle lowering, so Char
 * is never silently conflated with Int at runtime. Ordered by code point;
 * no arithmetic.
 */
public record CharValue(int codePoint) {

    public CharValue {
        if (!Character.isValidCodePoint(codePoint)) {
            throw new IllegalArgumentException(
                    "CharValue code point out of Unicode range: " + codePoint);
        }
    }

    @Override
    public String toString() {
        return "'" + render(codePoint) + "'";
    }

    /** The literal-form rendering of a code point (escapes for the v1 set). */
    public static String render(int codePoint) {
        return switch (codePoint) {
            case '\n' -> "\\n";
            case '\t' -> "\\t";
            case '\'' -> "\\'";
            case '\\' -> "\\\\";
            default -> new String(Character.toChars(codePoint));
        };
    }
}
