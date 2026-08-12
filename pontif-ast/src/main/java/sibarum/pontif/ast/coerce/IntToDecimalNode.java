package sibarum.pontif.ast.coerce;

import java.math.BigDecimal;
import java.util.List;

import com.oracle.truffle.api.frame.VirtualFrame;

import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.symbolic.RuntimeCheckException;

/**
 * The closed built-in {@code Int → Decimal} widening — the Truffle counterpart of
 * {@code IrInterpreter.evalCast}'s Decimal branch. A {@code Long} becomes
 * {@code BigDecimal.valueOf(n)} (the same scale-0 form the literal path produces);
 * a {@code BigDecimal} passes through (idempotent). The compiler inserts this for
 * an implicit {@code Int → Decimal} coercion at a value boundary (NumericCoercion),
 * so it is never desugared onto {@code +} — the conservation ledger records a
 * coercion, not an addition.
 */
public final class IntToDecimalNode extends PontifNode {

    @Child private PontifNode value;

    private IntToDecimalNode(PontifNode value) {
        this.value = value;
    }

    public static IntToDecimalNode of(PontifNode value) {
        if (value == null) {
            throw new IllegalArgumentException("IntToDecimalNode value must be non-null");
        }
        return new IntToDecimalNode(value);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object v = value.execute(frame);
        if (v instanceof Long l) {
            return BigDecimal.valueOf(l);
        }
        if (v instanceof BigDecimal) {
            return v;
        }
        throw new RuntimeCheckException(
                "Cannot widen " + (v == null ? "null" : v.getClass().getSimpleName())
                        + " to Decimal — the Int→Decimal tower widens an Int value only",
                origin());
    }

    @Override
    public List<PontifNode> children() {
        return List.of(value);
    }
}
