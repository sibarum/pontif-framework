package sibarum.pontif.core.types;

/**
 * Runtime string value — the first Char <em>collection</em>'s storage
 * representation (native-backed, the Char analog of {@code Array}). A wrapper
 * record, never a bare {@link String}: language strings must never be conflated
 * with the compiler-internal Java strings (type names, FQNs, operator symbols)
 * that pervade the value-decode chains. Ordered lexicographically by code
 * point; no arithmetic, no indexing — the stream view ({@code String ->
 * Stream(Char)}) is the iteration API, a later slice. Storage is
 * representation; "no array type" holds at the semantic level.
 */
public record StringValue(String content) {

    public StringValue {
        if (content == null) {
            throw new IllegalArgumentException("StringValue content must be non-null");
        }
    }

    @Override
    public String toString() {
        return "\"" + render(content) + "\"";
    }

    /** The literal-form rendering of a string (escapes for the v1 set). */
    public static String render(String content) {
        StringBuilder sb = new StringBuilder(content.length() + 2);
        content.codePoints().forEach(cp -> sb.append(renderCodePoint(cp)));
        return sb.toString();
    }

    private static String renderCodePoint(int codePoint) {
        return switch (codePoint) {
            case '\n' -> "\\n";
            case '\t' -> "\\t";
            case '"' -> "\\\"";
            case '\\' -> "\\\\";
            default -> new String(Character.toChars(codePoint));
        };
    }
}
