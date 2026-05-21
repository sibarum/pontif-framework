package sibarum.pontif.ast.binary;

import sibarum.pontif.core.PontifNode;

public final class Mul extends BinaryOp {

    private Mul(PontifNode left, PontifNode right) {
        super(left, right);
    }

    public static Mul of(PontifNode left, PontifNode right) {
        return new Mul(left, right);
    }

    @Override
    protected Object combine(Object leftValue, Object rightValue) {
        return (Long) leftValue * (Long) rightValue;
    }
}
