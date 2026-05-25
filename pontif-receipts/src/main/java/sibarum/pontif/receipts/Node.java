package sibarum.pontif.receipts;

import java.util.List;

/**
 * A call site in the receipt-graph. Each function's root is a {@code Node};
 * sub-call references (including recursive back-references) are encoded by
 * function name in {@link CallRef}, not by Node identity.
 *
 * <p>{@link #branches} carries one entry per arm of an internal match, or a
 * single unconditional branch if the body has no match.
 */
public record Node(
        String functionName,
        List<Param> params,
        Var resultVar,
        List<Branch> branches) {

    public Node {
        if (functionName == null || functionName.isEmpty()) {
            throw new IllegalArgumentException("Node functionName must be non-empty");
        }
        if (resultVar == null) {
            throw new IllegalArgumentException("Node resultVar must be non-null");
        }
        params = List.copyOf(params);
        branches = List.copyOf(branches);
        if (branches.isEmpty()) {
            throw new IllegalArgumentException("Node must have at least one branch");
        }
    }
}
