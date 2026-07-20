package sibarum.pontif.ast.literal;

import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.types.RecordValue;

import java.util.List;

/**
 * Truffle literal node for a metareference — built from statics only. The value is the
 * first-class metaref {@link RecordValue} (see {@link sibarum.pontif.core.types.Metaref}).
 */
public final class DispatchRefLiteral extends PontifNode {

    private final RecordValue value;

    private DispatchRefLiteral(RecordValue value) {
        this.value = value;
    }

    public static DispatchRefLiteral of(RecordValue value) {
        return new DispatchRefLiteral(value);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return value;
    }

    @Override
    public List<PontifNode> children() {
        return List.of();
    }
}
