package sibarum.pontif.ast.func;

import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.PontifNode;

import java.util.List;

/**
 * Wraps a function body. Unpacks frame arguments into the body's parameter
 * frame slots before evaluating the body. Used as the entry point of a function's
 * PontifRootNode tree.
 */
public final class FunctionEntryNode extends PontifNode {

    @Child private PontifNode body;
    private final int[] paramSlots;

    public FunctionEntryNode(int[] paramSlots, PontifNode body) {
        this.paramSlots = paramSlots.clone();
        this.body = body;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        for (int i = 0; i < paramSlots.length; i++) {
            frame.setObject(paramSlots[i], args[i]);
        }
        return body.execute(frame);
    }

    @Override
    public List<PontifNode> children() {
        return List.of(body);
    }
}
