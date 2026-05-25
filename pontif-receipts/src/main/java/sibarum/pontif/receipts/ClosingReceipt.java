package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.SymExpr;

import java.util.Map;

/**
 * A closing receipt — produced by an issuer to discharge an obligation,
 * separate from the receipt-graph (referenced via {@link GraphReference},
 * not embedded).
 *
 * <p>The notary reads only {@link #issuer}, {@link #conclusion}, and
 * {@link #reference}. The {@link #payload} is opaque to the notary — it
 * exists for 3rd-party verifiers (audit trails, proof certificates that
 * someone else might verify, debug info, etc.).
 */
public record ClosingReceipt(
        String issuer,
        SymExpr conclusion,
        GraphReference reference,
        Map<String, Object> payload) {

    public ClosingReceipt {
        if (issuer == null || issuer.isEmpty()) {
            throw new IllegalArgumentException("ClosingReceipt issuer must be non-empty");
        }
        if (conclusion == null) {
            throw new IllegalArgumentException("ClosingReceipt conclusion must be non-null");
        }
        if (reference == null) {
            throw new IllegalArgumentException("ClosingReceipt reference must be non-null");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
