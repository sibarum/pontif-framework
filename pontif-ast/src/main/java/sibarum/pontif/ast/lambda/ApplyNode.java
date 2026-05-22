package sibarum.pontif.ast.lambda;

import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.symbolic.RuntimeCheckException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ApplyNode extends PontifNode {

    @Child private PontifNode fnExpr;
    @Children private final PontifNode[] argNodes;

    private ApplyNode(PontifNode fnExpr, PontifNode[] argNodes) {
        this.fnExpr = fnExpr;
        this.argNodes = argNodes;
    }

    public static ApplyNode of(PontifNode fnExpr, PontifNode[] argNodes) {
        if (fnExpr == null) {
            throw new IllegalArgumentException("ApplyNode requires a function expression");
        }
        return new ApplyNode(fnExpr, argNodes);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object fnValue = fnExpr.execute(frame);
        if (!(fnValue instanceof LambdaValue lambda)) {
            throw new RuntimeCheckException(
                    "Apply expects a closure value, got "
                            + (fnValue == null ? "null" : fnValue.getClass().getSimpleName())
                            + ": " + fnValue,
                    origin());
        }
        Object[] args = new Object[argNodes.length];
        for (int i = 0; i < argNodes.length; i++) {
            args[i] = argNodes[i].execute(frame);
        }
        try {
            return lambda.invoke(args);
        } catch (RuntimeCheckException rce) {
            if (rce.origin().isPresent()) {
                throw rce;
            }
            throw new RuntimeCheckException(rce.getMessage(), origin(), rce);
        }
    }

    @Override
    public List<PontifNode> children() {
        List<PontifNode> all = new ArrayList<>(1 + argNodes.length);
        all.add(fnExpr);
        all.addAll(Arrays.asList(argNodes));
        return all;
    }
}
