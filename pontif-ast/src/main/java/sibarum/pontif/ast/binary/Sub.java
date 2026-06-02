package sibarum.pontif.ast.binary;

import sibarum.pontif.core.PontifNode;

import java.math.BigDecimal;

public final class Sub extends BinaryOp {

    private Sub(PontifNode left, PontifNode right) {
        super(left, right);
    }

    public static Sub of(PontifNode left, PontifNode right) {
        return new Sub(left, right);
    }

    @Override
    protected Object combine(Object leftValue, Object rightValue) {
        if (leftValue instanceof BigDecimal || rightValue instanceof BigDecimal) {
            return asDecimal(leftValue, "-").subtract(asDecimal(rightValue, "-"));
        }
        return (Long) leftValue - (Long) rightValue;
    }
}
