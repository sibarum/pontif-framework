package sibarum.pontif.ast.binary;

import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.symbolic.RuntimeCheckException;

import java.math.BigDecimal;

/**
 * Power. {@code Int ^ Int} is repeated multiplication, exponent ≥ 0 (a negative
 * Int exponent isn't an integer and is rejected). {@code Decimal ^ Int} uses
 * {@link BigDecimal#pow}; a non-integer (transcendental) exponent is out of
 * scope and rejected. If either operand is Decimal the result is Decimal.
 */
public final class Pow extends BinaryOp {

    private Pow(PontifNode left, PontifNode right) {
        super(left, right);
    }

    public static Pow of(PontifNode left, PontifNode right) {
        return new Pow(left, right);
    }

    @Override
    protected Object combine(Object leftValue, Object rightValue) {
        if (leftValue instanceof BigDecimal || rightValue instanceof BigDecimal) {
            BigDecimal base = asDecimal(leftValue, "^");
            BigDecimal exp = asDecimal(rightValue, "^");
            int e;
            try {
                e = exp.intValueExact();
            } catch (ArithmeticException notInt) {
                throw new RuntimeCheckException(
                        "Non-integer exponent " + exp.toPlainString() + " — a Decimal raised to a "
                                + "non-integer power is transcendental (out of scope)", origin());
            }
            if (e < 0) throw new RuntimeCheckException(
                    "Negative exponent " + e + " on " + base.toPlainString()
                            + " — only non-negative integer powers are supported", origin());
            return base.pow(e);
        }
        long base = (Long) leftValue;
        long exp = (Long) rightValue;
        if (exp < 0) throw new RuntimeCheckException(
                "Negative exponent on Int: " + base + " ^ " + exp
                        + " — not an integer; use Decimal for fractional powers", origin());
        long acc = 1L;
        for (long i = 0; i < exp; i++) acc *= base;
        return acc;
    }
}
