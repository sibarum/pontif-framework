package sibarum.pontif.ast.binary;

import sibarum.pontif.core.PontifNode;

import java.math.BigDecimal;

public final class Cmp extends BinaryOp {

    public enum Op { LT, LE, GT, GE, EQ, NE, APPROX }

    private final Op op;

    private Cmp(PontifNode left, PontifNode right, Op op) {
        super(left, right);
        this.op = op;
    }

    public static Cmp of(PontifNode left, PontifNode right, Op op) {
        return new Cmp(left, right, op);
    }

    public Op op() {
        return op;
    }

    @Override
    protected Object combine(Object leftValue, Object rightValue) {
        if (leftValue instanceof BigDecimal || rightValue instanceof BigDecimal) {
            BigDecimal a = asDecimal(leftValue, op.name());
            BigDecimal b = asDecimal(rightValue, op.name());
            int c = a.compareTo(b);
            return switch (op) {
                case LT -> c < 0;
                case LE -> c <= 0;
                case GT -> c > 0;
                case GE -> c >= 0;
                case EQ -> c == 0;
                case NE -> c != 0;
                // Equal within one ulp at the working precision (see Decimals).
                case APPROX -> sibarum.pontif.core.Decimals.approxEqual(a, b);
            };
        }
        long l = (Long) leftValue;
        long r = (Long) rightValue;
        return switch (op) {
            case LT -> l < r;
            case LE -> l <= r;
            case GT -> l > r;
            case GE -> l >= r;
            case EQ -> l == r;
            case NE -> l != r;
            case APPROX -> l == r;  // no rounding → ~= is ==
        };
    }
}
