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
        return combine(left.execute(frame), right.execute(frame));
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
        return v == null ? "null" : v.getClass().getSimpleName();
    }
}
