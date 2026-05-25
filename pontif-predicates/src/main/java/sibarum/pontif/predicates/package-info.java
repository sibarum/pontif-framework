/**
 * Predicate arithmetic kernel for Pontif: intersection, union, complement,
 * and satisfiability over a sort's domain.
 *
 * <p>Used by dispatch overload-overlap checking, match totality proving,
 * and match {@code _} default arm desugaring — the three places that
 * reduce to predicate arithmetic / SAT-lite reasoning.
 *
 * <p>Three-valued semantics: {@link SatResult.Yes}, {@link SatResult.No},
 * {@link SatResult.Unknown}. The kernel is principled about its limits
 * — when reasoning falls outside the supported fragment, it returns
 * {@code Unknown} with a reason rather than guessing. Callers treat
 * {@code Unknown} as a hard fail (no "maybe overlapping" overloads
 * ship); future oracle modules could escalate unknowns to richer
 * solvers.
 */
package sibarum.pontif.predicates;
