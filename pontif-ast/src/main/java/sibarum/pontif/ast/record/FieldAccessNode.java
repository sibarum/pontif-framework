package sibarum.pontif.ast.record;
import sibarum.pontif.core.types.RecordValue;

import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.symbolic.RuntimeCheckException;

import java.util.List;

public final class FieldAccessNode extends PontifNode {

    @Child private PontifNode base;
    private final String fieldName;

    private FieldAccessNode(PontifNode base, String fieldName) {
        this.base = base;
        this.fieldName = fieldName;
    }

    public static FieldAccessNode of(PontifNode base, String fieldName) {
        if (base == null) {
            throw new IllegalArgumentException("FieldAccessNode base must be non-null");
        }
        if (fieldName == null || fieldName.isEmpty()) {
            throw new IllegalArgumentException("FieldAccessNode field name must be non-empty");
        }
        return new FieldAccessNode(base, fieldName);
    }

    public String fieldName() {
        return fieldName;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object baseValue = base.execute(frame);
        // Decimal anatomy projection — total; unscaled is the canonical
        // scale-0 Decimal (never an Int: one-way wall). Mirrors IrInterpreter.
        if (baseValue instanceof java.math.BigDecimal dec) {
            if (!sibarum.pontif.core.Decimals.isAnatomyField(fieldName)) {
                throw new RuntimeCheckException(
                        "Decimal has no field '." + fieldName
                                + "' — its anatomy is (unscaled, scale)",
                        origin());
            }
            return "scale".equals(fieldName)
                    ? (Object) sibarum.pontif.core.Decimals.projectScale(dec)
                    : sibarum.pontif.core.Decimals.projectUnscaled(dec);
        }
        if (!(baseValue instanceof RecordValue record)) {
            throw new RuntimeCheckException(
                    "Field access '." + fieldName + "' requires a record value, got "
                            + (baseValue == null ? "null" : baseValue.getClass().getSimpleName())
                            + ": " + baseValue,
                    origin());
        }
        return record.get(fieldName, origin());
    }

    @Override
    public List<PontifNode> children() {
        return List.of(base);
    }
}
