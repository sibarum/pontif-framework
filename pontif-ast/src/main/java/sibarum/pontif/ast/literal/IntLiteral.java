package sibarum.pontif.ast.literal;

import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.PontifNode;

import java.util.List;

public final class IntLiteral extends PontifNode {

    private final long value;

    private IntLiteral(long value) {
        this.value = value;
    }

    public static IntLiteral of(long value) {
        return new IntLiteral(value);
    }

    public long value() {
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
