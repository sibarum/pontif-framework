package sibarum.pontif.ast.func;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.ast.lambda.LambdaValue;
import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.Resolver;
import sibarum.pontif.core.symbolic.DispatchResult;
import sibarum.pontif.core.symbolic.DispatchTable;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A call by name. Two execution paths, picked at runtime by lexical scope:
 *
 * <ol>
 *   <li>If {@code functionName} is bound in the enclosing scope (a let-binding
 *       or parameter holding a closure), the local binding wins. The bound value
 *       must be a {@link LambdaValue}; we invoke it directly. This is the
 *       "value-level call" path — same as if the user had written
 *       {@code (apply f args)}.</li>
 *   <li>Otherwise, dispatch through the {@link DispatchTable}, picking the
 *       overload whose refinement closes under the argument values. This is
 *       the "named-function call" path.</li>
 * </ol>
 *
 * The split is decided once at {@link #resolve(Resolver) resolve} time, based on
 * whether the {@code Resolver} contains the name. After resolution, the branch
 * is a single slot-check at execute time.
 */
public final class CallNode extends PontifNode {

    private final String functionName;
    @Children private final PontifNode[] argNodes;
    private final DispatchTable dispatch;
    private final Simplifier simplifier;
    private final FunctionRegistry registry;

    /** Frame slot of a locally-bound closure with this name, or -1 if none. */
    private int closureSlot = -1;

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
    public void resolve(Resolver resolver) {
        if (resolver.contains(functionName)) {
            closureSlot = resolver.lookup(functionName);
        }
        for (PontifNode child : children()) {
            child.resolve(resolver);
        }
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Local binding wins over the dispatch table — lexical scope.
        if (closureSlot >= 0) {
            return executeAsClosure(frame);
        }
        return executeAsDispatch(frame);
    }

    private Object executeAsClosure(VirtualFrame frame) {
        Object fnValue = frame.getObject(closureSlot);
        // A bound metareference: application reruns registry dispatch under
        // the REFERENCED name — candidates and narrowings intact.
        if (sibarum.pontif.core.types.Metaref.is(fnValue)) {
            int arity = sibarum.pontif.core.types.Metaref.keySorts(fnValue).size();
            if (argNodes.length != arity) {
                throw new RuntimeCheckException(
                        "Metareference " + fnValue + " takes " + arity
                                + " argument(s); got " + argNodes.length, origin());
            }
            return executeAsDispatch(frame, sibarum.pontif.core.types.Metaref.functionName(fnValue));
        }
        if (!(fnValue instanceof LambdaValue lambda)) {
            throw new RuntimeCheckException(
                    "'" + functionName + "' is bound locally but is not a closure; "
                            + "got " + (fnValue == null ? "null" : fnValue.getClass().getSimpleName())
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

    private Object executeAsDispatch(VirtualFrame frame) {
        return executeAsDispatch(frame, functionName);
    }

    /** Registry dispatch under {@code name} — shared by direct calls and
     * metareference application (where {@code name} is the referenced
     * function, not the bound variable). */
    private Object executeAsDispatch(VirtualFrame frame, String name) {
        Object[] args = new Object[argNodes.length];
        List<SymExpr> argSymbolics = new ArrayList<>();
        for (int i = 0; i < argNodes.length; i++) {
            args[i] = argNodes[i].execute(frame);
            argSymbolics.add(toSymExpr(args[i]));
        }

        DispatchResult dr = dispatch.resolve(name, argSymbolics, simplifier);
        return switch (dr) {
            case DispatchResult.NoMatch nm -> {
                // Application through a top-level binding (zero-arg function
                // holding a metareference): evaluate it; if it's a dispatch
                // value, re-dispatch under the referenced name.
                if (argNodes.length > 0
                        && dispatch.resolve(name, List.of(), simplifier)
                                instanceof DispatchResult.Resolved z) {
                    CallTarget zt = registry.callTarget(z.decl());
                    Object zeroArg = zt == null ? null : zt.call();
                    if (sibarum.pontif.core.types.Metaref.is(zeroArg)) {
                        yield executeAsDispatch(
                                frame, sibarum.pontif.core.types.Metaref.functionName(zeroArg));
                    }
                    // ...or holding a CLOSURE. A top-level `let f:[Method(Int):Int] = [(x:Int)
                    // -> …]` lowers to a zero-argument function returning a LambdaValue, so
                    // `f(5)` finds no 1-parameter overload and lands here. The interpreter has
                    // always invoked it; without this the Truffle engine answered "Dispatch
                    // failed for 'f'" for a program the interpreter ran — the same
                    // engine-disagreement shape as docs/soundness-holes.md family 9. Only a
                    // top-level binding reaches this path: inside a function body the closure is
                    // a frame slot and the closureSlot branch above already invokes it.
                    if (zeroArg instanceof LambdaValue lambda) {
                        try {
                            yield lambda.invoke(args);
                        } catch (RuntimeCheckException rce) {
                            throw rce.origin().isPresent()
                                    ? rce
                                    : new RuntimeCheckException(rce.getMessage(), origin(), rce);
                        }
                    }
                }
                throw new RuntimeCheckException(
                        "Dispatch failed for '" + name + "': " + nm.reason(),
                        origin());
            }
            case DispatchResult.Ambiguous a -> throw new RuntimeCheckException(
                    "Ambiguous dispatch for '" + name + "' between "
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
        return RuntimeDispatch.toSymExpr(value);
    }
}
