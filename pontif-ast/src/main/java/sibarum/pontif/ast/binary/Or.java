package sibarum.pontif.ast.binary;

import sibarum.pontif.core.PontifNode;

/**
 * Boolean disjunction. Strict (both operands evaluated). See {@link And} for
 * the rationale.
 */
public final class Or extends BinaryOp {

    private Or(PontifNode left, PontifNode right) {
        super(left, right);
    }

    public static Or of(PontifNode left, PontifNode right) {
        return new Or(left, right);
    }

    @Override
    protected Object combine(Object leftValue, Object rightValue) {
        return (Boolean) leftValue || (Boolean) rightValue;
    }
}
