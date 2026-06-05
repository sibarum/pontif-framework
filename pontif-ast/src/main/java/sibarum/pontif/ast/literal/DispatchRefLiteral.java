package sibarum.pontif.ast.literal;

import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.types.DispatchValue;

import java.util.List;

/** Truffle literal node for a metareference — built from statics only. */
public final class DispatchRefLiteral extends PontifNode {

    private final DispatchValue value;

    private DispatchRefLiteral(DispatchValue value) {
        this.value = value;
    }

    public static DispatchRefLiteral of(DispatchValue value) {
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
