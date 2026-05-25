package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.SymExpr;

/**
 * An initial / body receipt: a fact transcribed deterministically from the
 * source — a body equation (e.g., {@code r_0 = n_0 + n_0}) or an arm guard
 * restated as a positive assertion (e.g., {@code n_0 > 0}).
 *
 * <p>Produced by the {@link Drafter}; lives <em>inside</em> the receipt-graph
 * (distinct from {@link ClosingReceipt}, which is external).
 */
public record InitialReceipt(SymExpr claim) {

    public InitialReceipt {
        if (claim == null) {
            throw new IllegalArgumentException("InitialReceipt claim must be non-null");
        }
    }
}
