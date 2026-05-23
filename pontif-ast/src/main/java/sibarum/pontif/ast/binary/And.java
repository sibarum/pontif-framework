package sibarum.pontif.ast.binary;

import sibarum.pontif.core.PontifNode;

/**
 * Boolean conjunction. Strict (both operands evaluated). For short-circuit
 * semantics, a separate node would be needed; this matches the rest of the
 * binary-op family that pre-evaluates children via {@link BinaryOp}.
 */
public final class And extends BinaryOp {

    private And(PontifNode left, PontifNode right) {
        super(left, right);
    }

    public static And of(PontifNode left, PontifNode right) {
        return new And(left, right);
    }

    @Override
    protected Object combine(Object leftValue, Object rightValue) {
        return (Boolean) leftValue && (Boolean) rightValue;
    }
}
