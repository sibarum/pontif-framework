package sibarum.pontif.ast.binary;

import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.symbolic.RuntimeCheckException;

import java.math.BigDecimal;

/**
 * Remainder. {@code Int % Int} takes the sign of the dividend (Java semantics),
 * pairing with {@link Div} so {@code a == (a/b)*b + a%b}. {@code Decimal %
 * Decimal} uses {@link BigDecimal#remainder}.
 */
public final class Mod extends BinaryOp {

    private Mod(PontifNode left, PontifNode right) {
        super(left, right);
    }

    public static Mod of(PontifNode left, PontifNode right) {
        return new Mod(left, right);
    }

    @Override
    protected Object combine(Object leftValue, Object rightValue) {
        if (leftValue instanceof BigDecimal || rightValue instanceof BigDecimal) {
            BigDecimal r = (BigDecimal) rightValue;
            if (r.signum() == 0) throw new RuntimeCheckException("Decimal remainder by zero");
            return ((BigDecimal) leftValue).remainder(r);
        }
        long r = (Long) rightValue;
        if (r == 0L) throw new RuntimeCheckException("Integer remainder by zero");
        return (Long) leftValue % r;
    }
}
