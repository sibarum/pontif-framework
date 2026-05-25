package sibarum.pontif.predicates;

import sibarum.pontif.core.symbolic.SymExpr;

/**
 * Result of a predicate-complement computation.
 *
 * <ul>
 *   <li>{@link Computed} — the complement was computed; carries the
 *       resulting {@link SymExpr} predicate.
 *   <li>{@link Unknown} — the kernel's reasoning is insufficient.
 *       The {@link Unknown#reason} explains why.
 * </ul>
 *
 * <p>Same three-valued-ish principle as {@link SatResult}: the kernel
 * signals "I don't know" rather than guessing. Callers should treat
 * {@code Unknown} as a hard fail; for the match {@code _} desugar that
 * means erroring with "cannot infer default arm's predicate; write it
 * explicitly."
 */
public sealed interface ComplementResult permits ComplementResult.Computed, ComplementResult.Unknown {

    static ComplementResult computed(SymExpr predicate) { return new Computed(predicate); }
    static ComplementResult unknown(String reason) { return new Unknown(reason); }

    default boolean isComputed() { return this instanceof Computed; }
    default boolean isUnknown() { return this instanceof Unknown; }

    record Computed(SymExpr predicate) implements ComplementResult {
        public Computed {
            if (predicate == null) {
                throw new IllegalArgumentException("Computed predicate must be non-null");
            }
        }
    }

    record Unknown(String reason) implements ComplementResult {
        public Unknown {
            if (reason == null || reason.isEmpty()) {
                throw new IllegalArgumentException("Unknown reason must be non-empty");
            }
        }
    }
}
