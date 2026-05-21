package sibarum.pontif.ast.func;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.symbolic.DispatchResult;
import sibarum.pontif.core.symbolic.DispatchTable;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class CallNode extends PontifNode {

    private final String functionName;
    @Children private final PontifNode[] argNodes;
    private final DispatchTable dispatch;
    private final Simplifier simplifier;
    private final FunctionRegistry registry;

    private CallNode(
            String functionName,
            PontifNode[] argNodes,
            DispatchTable dispatch,
            Simplifier simplifier,
            FunctionRegistry registry) {
        this.functionName = functionName;
        this.argNodes = argNodes;
        this.dispatch = dispatch;
        this.simplifier = simplifier;
        this.registry = registry;
    }

    public static CallNode of(
            String functionName,
            PontifNode[] argNodes,
            DispatchTable dispatch,
            Simplifier simplifier,
            FunctionRegistry registry) {
        return new CallNode(functionName, argNodes, dispatch, simplifier, registry);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] args = new Object[argNodes.length];
        List<SymExpr> argSymbolics = new ArrayList<>();
        for (int i = 0; i < argNodes.length; i++) {
            args[i] = argNodes[i].execute(frame);
            argSymbolics.add(toSymExpr(args[i]));
        }

        DispatchResult dr = dispatch.resolve(functionName, argSymbolics, simplifier);
        return switch (dr) {
            case DispatchResult.NoMatch nm -> throw new RuntimeCheckException(
                    "Dispatch failed for '" + functionName + "': " + nm.reason(),
                    origin());
            case DispatchResult.Ambiguous a -> throw new RuntimeCheckException(
                    "Ambiguous dispatch for '" + functionName + "' between "
                            + a.candidates().size() + " candidate(s)",
                    origin());
            case DispatchResult.Resolved r -> {
                try {
                    r.call().executeChecks(Map.of(), simplifier);
                } catch (RuntimeCheckException rce) {
                    if (rce.origin().isPresent()) {
                        throw rce;
                    }
                    throw new RuntimeCheckException(rce.getMessage(), origin(), rce);
                }
                CallTarget callTarget = registry.callTarget(r.decl());
                if (callTarget == null) {
                    throw new IllegalStateException(
                            "Dispatch resolved to '" + r.decl().name()
                                    + "' but no CallTarget was registered for it");
                }
                yield callTarget.call(args);
            }
        };
    }

    @Override
    public List<PontifNode> children() {
        return Arrays.asList(argNodes);
    }

    private static SymExpr toSymExpr(Object value) {
        if (value instanceof Long l) return SymExpr.lit(l);
        if (value instanceof Integer i) return SymExpr.lit(i.longValue());
        if (value instanceof Boolean b) return SymExpr.bool(b);
        throw new IllegalArgumentException(
                "Cannot convert runtime value to SymExpr (type "
                        + (value == null ? "null" : value.getClass().getSimpleName()) + "): " + value);
    }
}
