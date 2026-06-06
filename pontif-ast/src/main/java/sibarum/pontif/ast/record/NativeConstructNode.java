package sibarum.pontif.ast.record;

import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.PontifNode;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Construction of a native-carrier value (a registered native constructor —
 * e.g. {@code Decimal(unscaled, scale)} → BigDecimal): evaluates the field
 * nodes in declared order and applies the registry's construct map. The map
 * is injected by the lowering (the registry lives above this module); its
 * contract is total and exact — the bijection half, no lossy path.
 */
public final class NativeConstructNode extends PontifNode {

    @Children private final PontifNode[] fields;
    private final BiFunction<Object[], Origin, Object> construct;

    private NativeConstructNode(PontifNode[] fields, BiFunction<Object[], Origin, Object> construct) {
        this.fields = fields;
        this.construct = construct;
    }

    public static NativeConstructNode of(
            List<PontifNode> fields, BiFunction<Object[], Origin, Object> construct) {
        if (construct == null) {
            throw new IllegalArgumentException("NativeConstructNode requires a construct map");
        }
        return new NativeConstructNode(fields.toArray(new PontifNode[0]), construct);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] values = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) {
            values[i] = fields[i].execute(frame);
        }
        return construct.apply(values, origin());
    }

    @Override
    public List<PontifNode> children() {
        return Arrays.asList(fields);
    }
}
