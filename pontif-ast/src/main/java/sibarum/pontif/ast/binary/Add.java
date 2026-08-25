package sibarum.pontif.ast.binary;

import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.types.CanonicalText;
import sibarum.pontif.core.types.StringValue;

import java.math.BigDecimal;

public final class Add extends BinaryOp {

    private Add(PontifNode left, PontifNode right) {
        super(left, right);
    }

    public static Add of(PontifNode left, PontifNode right) {
        return new Add(left, right);
    }

    /**
     * {@code +} is the one operator a String takes: concatenation. Every other operator
     * keeps failing closed on a String operand ("strings order and compare; they don't
     * compute"), which is why this opt-in is per-node rather than in {@link BinaryOp}.
     */
    @Override
    protected boolean acceptsString() {
        return true;
    }

    @Override
    protected Object combine(Object leftValue, Object rightValue) {
        // A String operand wins, checked BEFORE Decimal — `"x=" + d` concatenates rather
        // than trying to promote the String. Same precedence as the interpreter's
        // evalBinOp ladder; the rendering is the shared one so the two cannot drift.
        if (leftValue instanceof StringValue || rightValue instanceof StringValue) {
            return new StringValue(render(leftValue) + render(rightValue));
        }
        if (leftValue instanceof BigDecimal || rightValue instanceof BigDecimal) {
            return asDecimal(leftValue, "+").add(asDecimal(rightValue, "+"));
        }
        return (Long) leftValue + (Long) rightValue;
    }

    /** The other operand's canonical text, failing closed when it has none. */
    private String render(Object v) {
        String s = CanonicalText.of(v);
        if (s == null) {
            throw new RuntimeCheckException(
                    "Cannot concatenate " + (v == null ? "null" : v.getClass().getSimpleName())
                            + " with a String", origin());
        }
        return s;
    }
}
