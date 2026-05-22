package sibarum.pontif.ast.record;

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
