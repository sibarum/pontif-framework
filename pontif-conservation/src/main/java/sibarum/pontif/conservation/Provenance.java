package sibarum.pontif.conservation;

/**
 * Where a value came from, as far as the ledger can tell. {@code Opaque} is
 * the honest-ignorance case — the no-lie law applied to the ledger itself:
 * what v1 cannot trace is never reported as conserved or dropped, and every
 * conservation query fails closed on it.
 */
public sealed interface Provenance {

    /** The value IS an input attribute (or a whole input aggregate), verbatim. */
    record Path(AttributePath path) implements Provenance {}

    /** A value produced by a {@link Event.Combine} — identified by its derived id. */
    record Derived(String id) implements Provenance {}

    /** A literal — carries no input content. */
    record Constant(String rendering) implements Provenance {}

    /** The result of a recorded {@link Event.Call} — by-reference, untraced in v1. */
    record CallResult(String id) implements Provenance {}

    /** Untraceable in v1 (nested construction, lambda, nested match, …). */
    record Opaque(String reason) implements Provenance {}

    default String render() {
        return switch (this) {
            case Path p -> p.path().toString();
            case Derived d -> d.id();
            case Constant c -> c.rendering();
            case CallResult c -> c.id();
            case Opaque o -> "?(" + o.reason() + ")";
        };
    }
}
