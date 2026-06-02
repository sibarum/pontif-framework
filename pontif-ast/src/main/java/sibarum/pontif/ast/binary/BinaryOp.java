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
     * Guards the decimal branch of a subclass's {@link #combine}: when either
     * operand is a {@code BigDecimal}, both must be — mixed Int/Decimal
     * operands are a clear, origin-carrying error rather than a
     * {@code ClassCastException}. (Int literals at Decimal-declared boundaries
     * are promoted at compile time; Int <em>values</em> are not.)
     */
    protected final void requireBothDecimal(Object left, Object right, String symbol) {
        if (!(left instanceof BigDecimal) || !(right instanceof BigDecimal)) {
            throw new RuntimeCheckException(
                    "Operator '" + symbol + "' applied to mixed " + typeName(left) + "/"
                            + typeName(right) + " operands — Int values aren't auto-promoted "
                            + "in arithmetic. Write the literal as a decimal (1 -> 1.0), or "
                            + "store it in a Decimal-declared field/binding so it promotes.",
                    origin());
        }
    }

    private static String typeName(Object v) {
        if (v instanceof Long || v instanceof Integer) return "Int";
        if (v instanceof BigDecimal) return "Decimal";
        if (v instanceof Boolean) return "Bool";
        return v == null ? "null" : v.getClass().getSimpleName();
    }
}
