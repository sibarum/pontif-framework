package sibarum.pontif.receipts;

/**
 * A reference into a {@link ReceiptGraph}: which node (by index into
 * {@link ReceiptGraph#roots()}), which branch.
 *
 * <p>A node <em>index</em> rather than a function name, so overloads —
 * which share a name but are distinct nodes — are referenced
 * unambiguously.
 *
 * <p><b>Intentionally simple for v1.</b> A list index is a fine same-JVM
 * reference for the built-in default issuer. If 3rd-party-issuer
 * integration later demands serialization stability or deeper structure
 * (a specific receipt within a branch, a path through nested matches),
 * this shape can grow. See {@code docs/receipt-graph.md} → "References
 * into the graph (intentionally underspecified)".
 */
public record GraphReference(int nodeIndex, int branchIndex) {

    public GraphReference {
        if (nodeIndex < 0) {
            throw new IllegalArgumentException("GraphReference nodeIndex must be non-negative");
        }
        if (branchIndex < 0) {
            throw new IllegalArgumentException("GraphReference branchIndex must be non-negative");
        }
    }
}
