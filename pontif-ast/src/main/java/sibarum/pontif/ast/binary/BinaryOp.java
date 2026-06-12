package sibarum.pontif.ast.binary;

import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.symbolic.RuntimeCheckException;

import java.math.BigDecimal;
import java.util.List;

public abstract class BinaryOp extends PontifNode {

    @Child private PontifNode left;
    @Child private PontifNode right;

    protected BinaryOp(PontifNode left, PontifNode right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public final Object execute(VirtualFrame frame) {
        Object l = left.execute(frame);
        Object r = right.execute(frame);
        // Chars order and compare; they don't compute. Only comparison nodes
        // opt in — everything else fails closed with a located error instead
        // of a ClassCastException.
        if (!acceptsChar() && (l instanceof sibarum.pontif.core.types.CharValue
                || r instanceof sibarum.pontif.core.types.CharValue)) {
            throw new RuntimeCheckException(
                    "Operator applied to a Char operand — chars order and compare; "
                            + "they don't compute (got " + l + ", " + r + ")", origin());
        }
        // Strings order and compare; they don't compute either (no arithmetic,
        // no indexing — concatenation is the stream `concat` combinator).
        if (!acceptsString() && (l instanceof sibarum.pontif.core.types.StringValue
                || r instanceof sibarum.pontif.core.types.StringValue)) {
            throw new RuntimeCheckException(
                    "Operator applied to a String operand — strings order and compare; "
                            + "they don't compute (got " + l + ", " + r + ")", origin());
        }
        return combine(l, r);
    }

    /** Whether this node accepts Char operands (comparison nodes only). */
    protected boolean acceptsChar() {
        return false;
    }

    /** Whether this node accepts String operands (comparison nodes only). */
    protected boolean acceptsString() {
        return false;
    }

    @Override
    public final List<PontifNode> children() {
        return List.of(left, right);
    }

    protected abstract Object combine(Object leftValue, Object rightValue);

    /**
     * Coerces an operand of a subclass's decimal branch to BigDecimal. Int
     * promotes — the lossless direction of the embedding ({@code Decimal op
     * Int} is Decimal, matching the static sort); anything else meeting a
     * Decimal is a clear, origin-carrying error rather than a
     * {@code ClassCastException}.
     */
    protected final BigDecimal asDecimal(Object v, String symbol) {
        if (v instanceof BigDecimal d) return d;
        if (v instanceof Long n) return BigDecimal.valueOf(n);
        if (v instanceof Integer n) return BigDecimal.valueOf(n);
        throw new RuntimeCheckException(
                "Operator '" + symbol + "' applied to " + typeName(v)
                        + " and Decimal operands — only Int promotes to Decimal.",
                origin());
    }

    private static String typeName(Object v) {
        if (v instanceof Long || v instanceof Integer) return "Int";
        if (v instanceof BigDecimal) return "Decimal";
        if (v instanceof Boolean) return "Bool";
        if (v instanceof sibarum.pontif.core.types.CharValue) return "Char";
        if (v instanceof sibarum.pontif.core.types.StringValue) return "String";
        return v == null ? "null" : v.getClass().getSimpleName();
    }
}
