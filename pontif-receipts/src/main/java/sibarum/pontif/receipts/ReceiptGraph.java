package sibarum.pontif.receipts;

import java.util.List;

/**
 * Pontif's receipt-graph: the immutable data structure produced by the
 * {@link Drafter} from source. One artifact per module compilation; an
 * ordered list of function {@link Node}s, one per function declaration
 * (so overloads — multiple declarations sharing a name — each get their
 * own node; their distinct parameter sorts tell them apart).
 *
 * <p>Order is the source declaration order, preserved.
 *
 * <p>Recursive and inter-function calls are encoded by name via
 * {@link CallRef#targetFunctionName()}; a CallRef whose name matches the
 * {@link Node#functionName()} of a node in {@link #roots} <em>is</em> the
 * back-reference. (When several overloads share that name, the reference
 * is to the function by name; pinning the specific overload a call
 * dispatches to is deferred — see {@code docs/TODO.md}.)
 *
 * <p>See {@code docs/receipt-graph.md} for the design and worked example.
 */
public record ReceiptGraph(List<Node> roots) {

    public ReceiptGraph {
        if (roots == null) {
            throw new IllegalArgumentException("ReceiptGraph roots must be non-null");
        }
        roots = List.copyOf(roots);
    }

    /** All nodes declared under {@code functionName}, in declaration order. */
    public List<Node> nodesNamed(String functionName) {
        return roots.stream()
                .filter(n -> n.functionName().equals(functionName))
                .toList();
    }
}
