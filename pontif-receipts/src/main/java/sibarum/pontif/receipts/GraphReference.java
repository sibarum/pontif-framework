package sibarum.pontif.receipts;

/**
 * A reference into a {@link ReceiptGraph}: which function's root, which branch.
 *
 * <p><b>Intentionally simple for v1.</b> If 3rd-party-issuer integration later
 * demands deeper structure (a specific receipt within a branch, a path through
 * nested matches), fields can be added forward-compatibly. See
 * {@code docs/receipt-graph.md} → "References into the graph (intentionally
 * underspecified)".
 */
public record GraphReference(String functionName, int branchIndex) {

    public GraphReference {
        if (functionName == null || functionName.isEmpty()) {
            throw new IllegalArgumentException("GraphReference functionName must be non-empty");
        }
        if (branchIndex < 0) {
            throw new IllegalArgumentException("GraphReference branchIndex must be non-negative");
        }
    }
}
