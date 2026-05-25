package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.SymExpr;

import java.util.List;

/**
 * Reference to a sub-call from within a {@link Branch}. The target is
 * identified by function name, not by Node identity — so back-references
 * (recursive calls) are simply CallRefs whose {@link #targetFunctionName}
 * matches an existing key in the enclosing {@link ReceiptGraph#roots()}.
 *
 * <p>Each CallRef introduces its own fresh {@link #resultVar} (e.g.,
 * {@code r_1} for the first sub-call from the root, {@code r_2} for the next).
 * The argument bindings are the SymExpr arguments at the call site.
 */
public record CallRef(
        String targetFunctionName,
        List<SymExpr> argBindings,
        Var resultVar) {

    public CallRef {
        if (targetFunctionName == null || targetFunctionName.isEmpty()) {
            throw new IllegalArgumentException("CallRef targetFunctionName must be non-empty");
        }
        if (resultVar == null) {
            throw new IllegalArgumentException("CallRef resultVar must be non-null");
        }
        argBindings = List.copyOf(argBindings);
    }
}
