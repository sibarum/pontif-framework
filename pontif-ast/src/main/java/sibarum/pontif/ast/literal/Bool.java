package sibarum.pontif.ast.literal;

import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.PontifNode;

import java.util.List;

public final class Bool extends PontifNode {

    private final boolean value;

    private Bool(boolean value) {
        this.value = value;
    }

    public static Bool of(boolean value) {
        return new Bool(value);
    }

    public boolean value() {
        return value;
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
