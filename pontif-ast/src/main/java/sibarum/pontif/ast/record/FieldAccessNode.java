package sibarum.pontif.ast.record;
import sibarum.pontif.core.types.RecordValue;

import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.symbolic.RuntimeCheckException;

import java.util.List;

public final class FieldAccessNode extends PontifNode {

    @Child private PontifNode base;
    private final String fieldName;

    /**
     * Runtime dispatch for the trait-view attribute fallback, or null when the node was built
     * without one (a direct unit-test construction) — in which case a missing field is simply a
     * missing field, as it was before.
     */
    private final sibarum.pontif.ast.func.RuntimeDispatch dispatch;

    private FieldAccessNode(
            PontifNode base, String fieldName, sibarum.pontif.ast.func.RuntimeDispatch dispatch) {
        this.base = base;
        this.fieldName = fieldName;
        this.dispatch = dispatch;
    }

    public static FieldAccessNode of(
            PontifNode base, String fieldName, sibarum.pontif.ast.func.RuntimeDispatch dispatch) {
        if (base == null) {
            throw new IllegalArgumentException("FieldAccessNode base must be non-null");
        }
        if (fieldName == null || fieldName.isEmpty()) {
            throw new IllegalArgumentException("FieldAccessNode field name must be non-empty");
        }
        return new FieldAccessNode(base, fieldName, dispatch);
    }

    /** Without runtime dispatch: a missing field is a missing field, with no producer fallback. */
    public static FieldAccessNode of(PontifNode base, String fieldName) {
        return of(base, fieldName, null);
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
        if (record.members().containsKey(fieldName)) {
            return record.get(fieldName, origin());
        }
        // Trait-view attribute access: the value carries no such STORED field, so resolve a
        // computed projection — a `Type.attr(this)` producer registered by an `assign trait`
        // block. This is what lets a struct be viewed through a trait that adds attributes, and
        // the interpreter has always done it; without it the Truffle engine answered "Record has
        // no field 'weight'" for a program the interpreter ran (docs/soundness-holes.md — the
        // engines must agree about what a program means).
        Object projected = tryAttributeProducer(record);
        if (projected != sibarum.pontif.ast.func.RuntimeDispatch.NO_MATCH) {
            return projected;
        }
        return record.get(fieldName, origin());   // re-throws the "no field" error
    }

    /**
     * The producer's result, or {@code NO_MATCH}. Resolution is tried under both the qualified
     * and the bare type spelling, because the linker module-qualifies a type name and a producer
     * declared against the bare one keeps its own key — the same two-spelling lookup the
     * interpreter makes.
     */
    private Object tryAttributeProducer(RecordValue record) {
        if (dispatch == null || record.typeName() == null) {
            return sibarum.pontif.ast.func.RuntimeDispatch.NO_MATCH;
        }
        String bare = sibarum.pontif.core.QualifiedName.memberOf(record.typeName());
        for (String key : List.of(record.typeName() + "." + fieldName, bare + "." + fieldName)) {
            Object result = dispatch.tryInvoke(key, new Object[]{record});
            if (result != sibarum.pontif.ast.func.RuntimeDispatch.NO_MATCH) {
                return result;
            }
        }
        return sibarum.pontif.ast.func.RuntimeDispatch.NO_MATCH;
    }

    @Override
    public List<PontifNode> children() {
        return List.of(base);
    }
}
