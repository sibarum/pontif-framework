package sibarum.pontif.ast.lambda;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.Resolver;

import java.util.List;

public final class LambdaNode extends PontifNode {

    private final CallTarget callTarget;
    private final String[] captureNames;
    private final int[] captureSlots;
    private final int arity;

    private LambdaNode(CallTarget callTarget, String[] captureNames, int arity) {
        this.callTarget = callTarget;
        this.captureNames = captureNames.clone();
        this.captureSlots = new int[captureNames.length];
        for (int i = 0; i < captureSlots.length; i++) {
            captureSlots[i] = -1;
        }
        this.arity = arity;
    }

    public static LambdaNode of(CallTarget callTarget, List<String> captureNames, int arity) {
        if (callTarget == null) {
            throw new IllegalArgumentException("LambdaNode requires a CallTarget");
        }
        return new LambdaNode(callTarget, captureNames.toArray(new String[0]), arity);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] captures = new Object[captureSlots.length];
        for (int i = 0; i < captureSlots.length; i++) {
            if (captureSlots[i] < 0) {
                throw new IllegalStateException(
                        "LambdaNode capture '" + captureNames[i] + "' not resolved; "
                                + "call Pontif.eval (which runs Resolver) instead of constructing RootNodes directly");
            }
            captures[i] = frame.getObject(captureSlots[i]);
        }
        return new LambdaValue(callTarget, captures, arity, origin());
    }

    @Override
    public List<PontifNode> children() {
        return List.of();
    }

    @Override
    public void resolve(Resolver resolver) {
        for (int i = 0; i < captureNames.length; i++) {
            captureSlots[i] = resolver.lookup(captureNames[i]);
        }
    }
}
