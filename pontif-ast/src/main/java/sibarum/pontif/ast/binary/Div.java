package sibarum.pontif.ast.binary;

import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.symbolic.RuntimeCheckException;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Division. {@code Int / Int} truncates toward zero (pairs with {@link Mod} as an
 * information-conservative pair: {@code a == (a/b)*b + a%b}). {@code Decimal /
 * Decimal} rounds via {@link MathContext#DECIMAL128} — lossy by explicit policy.
 */
public final class Div extends BinaryOp {

    private Div(PontifNode left, PontifNode right) {
        super(left, right);
    }

    public static Div of(PontifNode left, PontifNode right) {
        return new Div(left, right);
    }

    @Override
    protected Object combine(Object leftValue, Object rightValue) {
        if (leftValue instanceof BigDecimal || rightValue instanceof BigDecimal) {
            BigDecimal r = asDecimal(rightValue, "/");
            if (r.signum() == 0) throw new RuntimeCheckException("Decimal division by zero");
            return asDecimal(leftValue, "/").divide(r, MathContext.DECIMAL128);
        }
        long r = (Long) rightValue;
        if (r == 0L) throw new RuntimeCheckException("Integer division by zero");
        return (Long) leftValue / r;
    }
}
