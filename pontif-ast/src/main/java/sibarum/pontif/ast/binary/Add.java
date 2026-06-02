package sibarum.pontif.ast.binary;

import sibarum.pontif.core.PontifNode;

import java.math.BigDecimal;

public final class Add extends BinaryOp {

    private Add(PontifNode left, PontifNode right) {
        super(left, right);
    }

    public static Add of(PontifNode left, PontifNode right) {
        return new Add(left, right);
    }

    @Override
    protected Object combine(Object leftValue, Object rightValue) {
        if (leftValue instanceof BigDecimal || rightValue instanceof BigDecimal) {
            return asDecimal(leftValue, "+").add(asDecimal(rightValue, "+"));
        }
        return (Long) leftValue + (Long) rightValue;
    }
}
