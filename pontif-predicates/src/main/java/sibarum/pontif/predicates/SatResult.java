package sibarum.pontif.predicates;

/**
 * Result of a satisfiability check. Three-valued — the kernel is principled
 * about its limits and signals "I don't know" rather than guessing.
 *
 * <ul>
 *   <li>{@link Yes} — there exists at least one value in the domain that
 *       satisfies the predicate.
 *   <li>{@link No} — no value in the domain satisfies the predicate;
 *       the predicate is unsatisfiable over the domain.
 *   <li>{@link Unknown} — the kernel's reasoning is insufficient to
 *       decide. The {@link Unknown#reason} explains why.
 * </ul>
 *
 * <p>Callers should treat {@code Unknown} as a hard fail unless they have
 * an oracle module to escalate to. "Possibly satisfiable" is not a result
 * Pontif ships on.
 */
public sealed interface SatResult permits SatResult.Yes, SatResult.No, SatResult.Unknown {

    static SatResult yes() { return Yes.INSTANCE; }
    static SatResult no() { return No.INSTANCE; }
    static SatResult unknown(String reason) { return new Unknown(reason); }

    /** Convenience: is this {@link Yes}? */
    default boolean isYes() { return this instanceof Yes; }

    /** Convenience: is this {@link No}? */
    default boolean isNo() { return this instanceof No; }

    /** Convenience: is this {@link Unknown}? */
    default boolean isUnknown() { return this instanceof Unknown; }

    record Yes() implements SatResult {
        public static final Yes INSTANCE = new Yes();
    }

    record No() implements SatResult {
        public static final No INSTANCE = new No();
    }

    record Unknown(String reason) implements SatResult {
        public Unknown {
            if (reason == null || reason.isEmpty()) {
                throw new IllegalArgumentException("Unknown reason must be non-empty");
            }
        }
    }
}
