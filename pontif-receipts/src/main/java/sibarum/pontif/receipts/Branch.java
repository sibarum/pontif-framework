package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.SymExpr;

import java.util.List;
import java.util.Optional;

/**
 * A single path through a {@link Node}.
 *
 * <ul>
 *   <li>{@link #guard} — the arm's predicate (a symbolic claim about the
 *       parameters), or {@link Optional#empty()} for an unconditional body.
 *   <li>{@link #initialReceipts} — facts that hold on this path, transcribed
 *       deterministically from source (body equation, arm guard restated).
 *   <li>{@link #calls} — sub-call references on this path.
 * </ul>
 */
public record Branch(
        Optional<SymExpr> guard,
        List<InitialReceipt> initialReceipts,
        List<CallRef> calls) {

    public Branch {
        if (guard == null) {
            throw new IllegalArgumentException("Branch guard must be a non-null Optional (use Optional.empty() for unconditional bodies)");
        }
        initialReceipts = List.copyOf(initialReceipts);
        calls = List.copyOf(calls);
    }
}
