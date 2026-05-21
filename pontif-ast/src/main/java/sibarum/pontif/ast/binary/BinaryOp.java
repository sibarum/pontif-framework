package sibarum.pontif.ast.binary;

import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.PontifNode;

import java.util.List;

public abstract class BinaryOp extends PontifNode {

    @Child private PontifNode left;
    @Child private PontifNode right;

    protected BinaryOp(PontifNode left, PontifNode right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public final Object execute(VirtualFrame frame) {
        return combine(left.execute(frame), right.execute(frame));
    }

    @Override
    public final List<PontifNode> children() {
        return List.of(left, right);
    }

    protected abstract Object combine(Object leftValue, Object rightValue);
}
