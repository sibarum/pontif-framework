package sibarum.pontif.ast.binary;

import sibarum.pontif.core.PontifNode;

public final class Cmp extends BinaryOp {

    public enum Op { LT, LE, GT, GE, EQ, NE }

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
        long l = (Long) leftValue;
        long r = (Long) rightValue;
        return switch (op) {
            case LT -> l < r;
            case LE -> l <= r;
            case GT -> l > r;
            case GE -> l >= r;
            case EQ -> l == r;
            case NE -> l != r;
        };
    }
}
