package sibarum.pontif.receipts;

import java.util.Map;

/**
 * Pontif's receipt-graph: the immutable data structure produced by the
 * {@link Drafter} from source. One artifact per module compilation; maps
 * function name → {@link Node} root.
 *
 * <p>Recursive and inter-function calls are encoded by name via
 * {@link CallRef#targetFunctionName()}; a CallRef whose name matches an
 * existing key in {@link #roots} <em>is</em> the back-reference.
 *
 * <p>See {@code docs/receipt-graph.md} for the design and worked example.
 */
public record ReceiptGraph(Map<String, Node> roots) {

    public ReceiptGraph {
        if (roots == null) {
            throw new IllegalArgumentException("ReceiptGraph roots must be non-null");
        }
        roots = Map.copyOf(roots);
    }
}
