package sibarum.pontif.predicates;

import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Predicate arithmetic kernel for Pontif.
 *
 * <p>Operations over symbolic predicates ({@link SymExpr}) parameterized by
 * a sort domain. Used by:
 * <ul>
 *   <li>Dispatch overload-overlap checking — is
 *       {@code pred_A ∧ pred_B} unsatisfiable?
 *   <li>Match totality proving — is {@code sort ∧ ¬(union of arms)}
 *       unsatisfiable?
 *   <li>Match {@code _} default arm desugar — complement of explicit
 *       arms over the scrutinee's sort.
 * </ul>
 *
 * <p>All three reduce to {@link #satisfiable(SymExpr, Sort) satisfiability}
 * (plus, eventually, complement). The kernel is three-valued: it returns
 * {@link SatResult.Unknown} when reasoning falls outside the supported
 * fragment, rather than guessing.
 *
 * <p><b>Current fragment over Int:</b>
 * <ul>
 *   <li>{@code @ op n} for {@code op ∈ {GT, GE, LT, LE, EQ, NE}} and
 *       integer literal {@code n} (or {@code n op @} with the same
 *       result).
 *   <li>{@link SymExpr.And} of supported predicates → interval intersection.
 *   <li>{@link SymExpr.Or} of supported predicates → interval union with
 *       overlap / adjacency collapse.
 *   <li>{@link SymExpr.Bool} literals (constant {@code true} / {@code false}
 *       predicates).
 * </ul>
 * Anything else (multiplication, function applications, non-Int bases,
 * etc.) returns {@code Unknown} with a reason. The kernel takes the
 * <i>narrowest</i> sort the caller has — a refined sort's predicate is
 * folded into the satisfiability check internally.
 */
public final class PredicateArithmetic {

    private PredicateArithmetic() {}

    /**
     * Is there any value in {@code domain} that satisfies {@code predicate}?
     *
     * @param predicate the predicate to check (a {@link SymExpr} typically
     *                  written in terms of {@link SymExpr.Self})
     * @param domain    the sort whose values are considered — can be a base
     *                  sort like {@code Sort.of("Int")} or a refined sort
     *                  like {@code Sort.refined("Int", ...)}; the
     *                  refinement (if any) is folded into the check
     */
    public static SatResult satisfiable(SymExpr predicate, Sort domain) {
        if (predicate == null) {
            throw new IllegalArgumentException("predicate must be non-null");
        }
        if (domain == null) {
            throw new IllegalArgumentException("domain must be non-null");
        }

        // Fold the domain's refinement (if any) into the predicate via AND.
        SymExpr effective = domain.isRefined()
                ? SymExpr.and(domain.predicate(), predicate)
                : predicate;

        if (!"Int".equals(domain.name())) {
            return SatResult.unknown(
                    "Current slice supports Int domain only; got base '" + domain.name() + "'");
        }

        return satisfiableOverInt(effective);
    }

    /**
     * Computes the predicate covering the values <em>in {@code domain}</em>
     * that do <em>not</em> satisfy {@code predicate}. Used by:
     * <ul>
     *   <li>Match {@code _} default arm desugar — the {@code _} arm's
     *       predicate is the complement of the explicit arms' union over
     *       the scrutinee's sort.
     *   <li>Match totality — the match is total iff the complement is
     *       unsatisfiable.
     * </ul>
     *
     * <p>The result is constrained to {@code domain} — i.e., it's
     * {@code domain.predicate ∧ ¬predicate}, not just {@code ¬predicate}
     * over the base sort. This is what consumers want: "the values left
     * over after the other arms covered theirs."
     *
     * <p>Returns {@link ComplementResult.Unknown} when {@code predicate}
     * or {@code domain.predicate()} falls outside the kernel's supported
     * fragment.
     */
    public static ComplementResult complement(SymExpr predicate, Sort domain) {
        if (predicate == null) {
            throw new IllegalArgumentException("predicate must be non-null");
        }
        if (domain == null) {
            throw new IllegalArgumentException("domain must be non-null");
        }

        if (!"Int".equals(domain.name())) {
            return ComplementResult.unknown(
                    "Current slice supports Int domain only; got base '" + domain.name() + "'");
        }

        IntervalSet predSet = toIntervalSet(predicate);
        if (predSet == null) {
            return ComplementResult.unknown(
                    "Predicate outside the integer-comparison fragment: " + predicate);
        }

        IntervalSet result = predSet.complement();

        if (domain.isRefined()) {
            IntervalSet domainSet = toIntervalSet(domain.predicate());
            if (domainSet == null) {
                return ComplementResult.unknown(
                        "Domain refinement outside the integer-comparison fragment: "
                                + domain.predicate());
            }
            result = result.intersect(domainSet);
        }

        return ComplementResult.computed(intervalSetToSymExpr(result));
    }

    // --- Internal: Int-domain satisfiability via interval-set arithmetic ----

    private static SatResult satisfiableOverInt(SymExpr predicate) {
        IntervalSet set = toIntervalSet(predicate);
        if (set == null) {
            return SatResult.unknown(
                    "Predicate outside the integer-comparison fragment: " + predicate);
        }
        return set.isEmpty() ? SatResult.no() : SatResult.yes();
    }

    /**
     * Tries to interpret {@code expr} as the set of integer values that
     * satisfy it (with {@code @} as the subject). Returns {@code null} for
     * shapes outside the current fragment.
     */
    private static IntervalSet toIntervalSet(SymExpr expr) {
        if (expr instanceof SymExpr.Bool b) {
            return b.value() ? IntervalSet.FULL : IntervalSet.EMPTY;
        }
        if (expr instanceof SymExpr.Cmp(SymExpr left, SymExpr.CmpOp op, SymExpr right)) {
            return cmpToIntervalSet(left, op, right);
        }
        if (expr instanceof SymExpr.And(SymExpr l, SymExpr r)) {
            IntervalSet lSet = toIntervalSet(l);
            if (lSet == null) return null;
            IntervalSet rSet = toIntervalSet(r);
            if (rSet == null) return null;
            return lSet.intersect(rSet);
        }
        if (expr instanceof SymExpr.Or(SymExpr l, SymExpr r)) {
            IntervalSet lSet = toIntervalSet(l);
            if (lSet == null) return null;
            IntervalSet rSet = toIntervalSet(r);
            if (rSet == null) return null;
            return lSet.union(rSet);
        }
        return null;
    }

    /**
     * Converts a comparison of {@code @} against an integer literal to an
     * interval set. Accepts either {@code @ op n} or {@code n op @}.
     */
    private static IntervalSet cmpToIntervalSet(SymExpr left, SymExpr.CmpOp op, SymExpr right) {
        long n;
        SymExpr.CmpOp effectiveOp;
        if (left instanceof SymExpr.Self && right instanceof SymExpr.Lit(long lit)) {
            n = lit;
            effectiveOp = op;
        } else if (right instanceof SymExpr.Self && left instanceof SymExpr.Lit(long lit)) {
            n = lit;
            effectiveOp = flip(op);
        } else {
            return null;  // unsupported shape
        }

        return switch (effectiveOp) {
            case GT -> n == Long.MAX_VALUE
                    ? IntervalSet.EMPTY
                    : IntervalSet.of(new Interval(n + 1, Long.MAX_VALUE));
            case GE -> IntervalSet.of(new Interval(n, Long.MAX_VALUE));
            case LT -> n == Long.MIN_VALUE
                    ? IntervalSet.EMPTY
                    : IntervalSet.of(new Interval(Long.MIN_VALUE, n - 1));
            case LE -> IntervalSet.of(new Interval(Long.MIN_VALUE, n));
            case EQ -> IntervalSet.of(new Interval(n, n));
            case NE -> neIntervalSet(n);
        };
    }

    /** {@code @ != n} = {@code (-∞, n-1] ∪ [n+1, ∞)}, with boundary care. */
    private static IntervalSet neIntervalSet(long n) {
        List<Interval> intervals = new ArrayList<>(2);
        if (n != Long.MIN_VALUE) intervals.add(new Interval(Long.MIN_VALUE, n - 1));
        if (n != Long.MAX_VALUE) intervals.add(new Interval(n + 1, Long.MAX_VALUE));
        // Naturally sorted and non-adjacent (gap at n); skip canonicalize.
        return intervals.isEmpty() ? IntervalSet.EMPTY : new IntervalSet(intervals);
    }

    /**
     * Converts an interval set back to a {@link SymExpr} predicate over
     * {@code @}. Inverse of {@link #toIntervalSet} for the
     * canonical-form interval-set fragment.
     */
    private static SymExpr intervalSetToSymExpr(IntervalSet set) {
        if (set.isEmpty()) return SymExpr.bool(false);
        List<Interval> intervals = set.intervals();
        if (intervals.size() == 1 && intervals.get(0).equals(Interval.FULL)) {
            return SymExpr.bool(true);
        }
        SymExpr result = intervalToSymExpr(intervals.get(0));
        for (int i = 1; i < intervals.size(); i++) {
            result = SymExpr.or(result, intervalToSymExpr(intervals.get(i)));
        }
        return result;
    }

    /** A single closed interval as a {@link SymExpr} predicate over {@code @}. */
    private static SymExpr intervalToSymExpr(Interval interval) {
        long min = interval.min();
        long max = interval.max();
        if (min == Long.MIN_VALUE && max == Long.MAX_VALUE) {
            return SymExpr.bool(true);
        }
        if (min == max) {
            return SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.EQ, SymExpr.lit(min));
        }
        if (min == Long.MIN_VALUE) {
            return SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.LE, SymExpr.lit(max));
        }
        if (max == Long.MAX_VALUE) {
            return SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(min));
        }
        return SymExpr.and(
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(min)),
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.LE, SymExpr.lit(max)));
    }

    /** Flips a comparison's operator so the subject moves from right to left. */
    private static SymExpr.CmpOp flip(SymExpr.CmpOp op) {
        return switch (op) {
            case LT -> SymExpr.CmpOp.GT;
            case LE -> SymExpr.CmpOp.GE;
            case GT -> SymExpr.CmpOp.LT;
            case GE -> SymExpr.CmpOp.LE;
            case EQ -> SymExpr.CmpOp.EQ;
            case NE -> SymExpr.CmpOp.NE;
        };
    }

    // --- Interval and IntervalSet -------------------------------------------

    /**
     * Closed integer interval {@code [min, max]}. Empty when
     * {@code min > max}. Internal to the kernel.
     */
    private record Interval(long min, long max) {
        static final Interval FULL = new Interval(Long.MIN_VALUE, Long.MAX_VALUE);
        static final Interval EMPTY = new Interval(0L, -1L);

        boolean isEmpty() { return min > max; }

        Interval intersect(Interval other) {
            long newMin = Math.max(this.min, other.min);
            long newMax = Math.min(this.max, other.max);
            return newMin > newMax ? EMPTY : new Interval(newMin, newMax);
        }
    }

    /**
     * A set of integer intervals, in canonical form: sorted by {@code min}
     * ascending, with no empty / overlapping / adjacent intervals.
     * Adjacency is integer-strict: {@code [a,b]} and {@code [b+1,c]}
     * collapse into {@code [a,c]}.
     */
    private record IntervalSet(List<Interval> intervals) {
        static final IntervalSet EMPTY = new IntervalSet(List.of());
        static final IntervalSet FULL = new IntervalSet(List.of(Interval.FULL));

        public IntervalSet {
            intervals = List.copyOf(intervals);
        }

        boolean isEmpty() { return intervals.isEmpty(); }

        static IntervalSet of(Interval i) {
            return i.isEmpty() ? EMPTY : new IntervalSet(List.of(i));
        }

        IntervalSet intersect(IntervalSet other) {
            List<Interval> result = new ArrayList<>();
            for (Interval a : this.intervals) {
                for (Interval b : other.intervals) {
                    Interval merged = a.intersect(b);
                    if (!merged.isEmpty()) result.add(merged);
                }
            }
            return canonicalize(result);
        }

        IntervalSet union(IntervalSet other) {
            List<Interval> all = new ArrayList<>(this.intervals.size() + other.intervals.size());
            all.addAll(this.intervals);
            all.addAll(other.intervals);
            return canonicalize(all);
        }

        /**
         * Complement over the full integer line: the "gaps" between the
         * intervals plus the regions before the first and after the last.
         * Result is canonical (sorted, non-overlapping, non-adjacent).
         */
        IntervalSet complement() {
            if (intervals.isEmpty()) return FULL;

            List<Interval> result = new ArrayList<>(intervals.size() + 1);
            Interval first = intervals.get(0);
            if (first.min() != Long.MIN_VALUE) {
                result.add(new Interval(Long.MIN_VALUE, first.min() - 1));
            }
            for (int i = 1; i < intervals.size(); i++) {
                Interval prev = intervals.get(i - 1);
                Interval next = intervals.get(i);
                // Canonical inputs guarantee prev.max + 1 < next.min, so the
                // gap interval is non-empty.
                result.add(new Interval(prev.max() + 1, next.min() - 1));
            }
            Interval last = intervals.get(intervals.size() - 1);
            if (last.max() != Long.MAX_VALUE) {
                result.add(new Interval(last.max() + 1, Long.MAX_VALUE));
            }

            return result.isEmpty() ? EMPTY : new IntervalSet(result);
        }

        /**
         * Sorts intervals, drops empties, and merges overlapping or
         * adjacent intervals into the canonical form.
         */
        private static IntervalSet canonicalize(List<Interval> input) {
            List<Interval> nonEmpty = new ArrayList<>(input.size());
            for (Interval i : input) {
                if (!i.isEmpty()) nonEmpty.add(i);
            }
            if (nonEmpty.isEmpty()) return EMPTY;
            nonEmpty.sort(Comparator.comparingLong(Interval::min));

            List<Interval> merged = new ArrayList<>();
            Interval current = nonEmpty.get(0);
            for (int i = 1; i < nonEmpty.size(); i++) {
                Interval next = nonEmpty.get(i);
                boolean overlapsOrAdjacent;
                if (current.max() == Long.MAX_VALUE) {
                    // Current already reaches +∞; anything sorted after is absorbed.
                    overlapsOrAdjacent = true;
                } else {
                    overlapsOrAdjacent = next.min() <= current.max() + 1;
                }
                if (overlapsOrAdjacent) {
                    long newMax = Math.max(current.max(), next.max());
                    current = new Interval(current.min(), newMax);
                } else {
                    merged.add(current);
                    current = next;
                }
            }
            merged.add(current);
            return new IntervalSet(merged);
        }
    }
}
