package sibarum.pontif.demo.posnat;

import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.PontifNode;

import java.util.List;

public final class PosLit extends PontifNode {

    private final long value;

    private PosLit(long value) {
        this.value = value;
    }

    public static PosLit of(long value) {
        return new PosLit(value);
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
