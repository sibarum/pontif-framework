package sibarum.pontif.predicates;

import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;

import java.math.BigDecimal;
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

        return switch (domain.name()) {
            case "Int" -> satisfiableOverInt(effective);
            case "Decimal" -> satisfiableOverDecimal(effective);
            case "Bool" -> satisfiableOverBool(effective);
            default -> SatResult.unknown(
                    "supported domains are Int, Decimal and Bool; got base '" + domain.name() + "'");
        };
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

        if ("Bool".equals(domain.name())) {
            return complementOverBool(predicate, domain);
        }
        if ("Decimal".equals(domain.name())) {
            return complementOverDecimal(predicate, domain);
        }
        if (!"Int".equals(domain.name())) {
            return ComplementResult.unknown(
                    "supported domains are Int, Decimal and Bool; got base '" + domain.name() + "'");
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

    // --- Internal: Bool-domain satisfiability via the two-element value set --

    private static SatResult satisfiableOverBool(SymExpr predicate) {
        BoolSet set = toBoolSet(predicate);
        if (set == null) {
            return SatResult.unknown("Predicate outside the boolean fragment: " + predicate);
        }
        return set.isEmpty() ? SatResult.no() : SatResult.yes();
    }

    private static ComplementResult complementOverBool(SymExpr predicate, Sort domain) {
        BoolSet predSet = toBoolSet(predicate);
        if (predSet == null) {
            return ComplementResult.unknown("Predicate outside the boolean fragment: " + predicate);
        }
        BoolSet result = predSet.complement();
        if (domain.isRefined()) {
            BoolSet domainSet = toBoolSet(domain.predicate());
            if (domainSet == null) {
                return ComplementResult.unknown(
                        "Domain refinement outside the boolean fragment: " + domain.predicate());
            }
            result = result.intersect(domainSet);
        }
        return ComplementResult.computed(boolSetToSymExpr(result));
    }

    /**
     * Interprets {@code expr} as the set of {@code Bool} values (with {@code @}
     * the subject) satisfying it. Bool has exactly two inhabitants, so the
     * "set" is one of four states. Returns {@code null} for shapes outside the
     * fragment — supported: {@code @ == true/false}, their {@code !=} forms,
     * {@code &}/{@code |} of those, and bare Bool literals.
     */
    private static BoolSet toBoolSet(SymExpr expr) {
        if (expr instanceof SymExpr.Bool b) {
            // A bare Bool literal is a constant predicate: `true` holds for
            // every value of @, `false` for none.
            return b.value() ? BoolSet.ALL : BoolSet.EMPTY;
        }
        if (expr instanceof SymExpr.Cmp(SymExpr left, SymExpr.CmpOp op, SymExpr right)) {
            return cmpToBoolSet(left, op, right);
        }
        if (expr instanceof SymExpr.And(SymExpr l, SymExpr r)) {
            BoolSet ls = toBoolSet(l);
            if (ls == null) return null;
            BoolSet rs = toBoolSet(r);
            if (rs == null) return null;
            return ls.intersect(rs);
        }
        if (expr instanceof SymExpr.Or(SymExpr l, SymExpr r)) {
            BoolSet ls = toBoolSet(l);
            if (ls == null) return null;
            BoolSet rs = toBoolSet(r);
            if (rs == null) return null;
            return ls.union(rs);
        }
        return null;
    }

    /** {@code @ == b} / {@code @ != b} against a Bool literal (either side). */
    private static BoolSet cmpToBoolSet(SymExpr left, SymExpr.CmpOp op, SymExpr right) {
        boolean val;
        if (left instanceof SymExpr.Self && right instanceof SymExpr.Bool(boolean b)) {
            val = b;
        } else if (right instanceof SymExpr.Self && left instanceof SymExpr.Bool(boolean b)) {
            val = b;
        } else {
            return null;
        }
        return switch (op) {
            case EQ -> val ? BoolSet.TRUE : BoolSet.FALSE;
            case NE -> val ? BoolSet.FALSE : BoolSet.TRUE;
            default -> null;  // ordering comparisons aren't meaningful on Bool
        };
    }

    /** Inverse of {@link #toBoolSet} for the canonical Bool-set shapes. */
    private static SymExpr boolSetToSymExpr(BoolSet set) {
        if (set.isEmpty()) return SymExpr.bool(false);
        if (set.hasTrue() && set.hasFalse()) return SymExpr.bool(true);
        return SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.EQ, SymExpr.bool(set.hasTrue()));
    }

    /**
     * The satisfying set over {@code Bool}'s two inhabitants — the boolean
     * counterpart of {@link IntervalSet} for the two-element domain.
     */
    private record BoolSet(boolean hasTrue, boolean hasFalse) {
        static final BoolSet EMPTY = new BoolSet(false, false);
        static final BoolSet ALL = new BoolSet(true, true);
        static final BoolSet TRUE = new BoolSet(true, false);
        static final BoolSet FALSE = new BoolSet(false, true);

        boolean isEmpty() { return !hasTrue && !hasFalse; }

        BoolSet intersect(BoolSet o) {
            return new BoolSet(hasTrue && o.hasTrue, hasFalse && o.hasFalse);
        }

        BoolSet union(BoolSet o) {
            return new BoolSet(hasTrue || o.hasTrue, hasFalse || o.hasFalse);
        }

        BoolSet complement() {
            return new BoolSet(!hasTrue, !hasFalse);
        }
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

    // --- Internal: Decimal-domain reasoning via dense-order interval sets ----
    //
    // The Int kernel above collapses strict bounds to integer neighbours
    // (`@>0` == `[1, ∞)`), which is unsound over a dense order — `@>0` and
    // `@>=0` differ on the reals. This mirror keeps OPEN vs CLOSED endpoints
    // explicitly (BigDecimal value + inclusive flag; a null endpoint is ±∞,
    // always exclusive), so order-only coverage like `@!=0` ⟺ `@<0 | @>0`
    // decides over Decimal exactly as it does over Int.

    private static SatResult satisfiableOverDecimal(SymExpr predicate) {
        DecIntervalSet set = toDecIntervalSet(predicate);
        if (set == null) {
            return SatResult.unknown(
                    "Predicate outside the decimal-comparison fragment: " + predicate);
        }
        return set.isEmpty() ? SatResult.no() : SatResult.yes();
    }

    private static ComplementResult complementOverDecimal(SymExpr predicate, Sort domain) {
        DecIntervalSet predSet = toDecIntervalSet(predicate);
        if (predSet == null) {
            return ComplementResult.unknown(
                    "Predicate outside the decimal-comparison fragment: " + predicate);
        }
        DecIntervalSet result = predSet.complement();
        if (domain.isRefined()) {
            DecIntervalSet domainSet = toDecIntervalSet(domain.predicate());
            if (domainSet == null) {
                return ComplementResult.unknown(
                        "Domain refinement outside the decimal-comparison fragment: "
                                + domain.predicate());
            }
            result = result.intersect(domainSet);
        }
        return ComplementResult.computed(decIntervalSetToSymExpr(result));
    }

    /** Interpret {@code expr} as the set of real values satisfying it ({@code @} the subject). */
    private static DecIntervalSet toDecIntervalSet(SymExpr expr) {
        if (expr instanceof SymExpr.Bool b) {
            return b.value() ? DecIntervalSet.FULL : DecIntervalSet.EMPTY;
        }
        if (expr instanceof SymExpr.Cmp(SymExpr left, SymExpr.CmpOp op, SymExpr right)) {
            return cmpToDecIntervalSet(left, op, right);
        }
        if (expr instanceof SymExpr.And(SymExpr l, SymExpr r)) {
            DecIntervalSet lSet = toDecIntervalSet(l);
            if (lSet == null) return null;
            DecIntervalSet rSet = toDecIntervalSet(r);
            if (rSet == null) return null;
            return lSet.intersect(rSet);
        }
        if (expr instanceof SymExpr.Or(SymExpr l, SymExpr r)) {
            DecIntervalSet lSet = toDecIntervalSet(l);
            if (lSet == null) return null;
            DecIntervalSet rSet = toDecIntervalSet(r);
            if (rSet == null) return null;
            return lSet.union(rSet);
        }
        return null;
    }

    /** A numeric leaf ({@code SymExpr.Lit} integer or {@code SymExpr.Dec}) as an exact BigDecimal. */
    private static BigDecimal asDecimalLiteral(SymExpr e) {
        if (e instanceof SymExpr.Lit(long v)) return BigDecimal.valueOf(v);
        if (e instanceof SymExpr.Dec(BigDecimal v)) return v;
        return null;
    }

    /** {@code @ op c} / {@code c op @} over the reals — strict ops give OPEN bounds. */
    private static DecIntervalSet cmpToDecIntervalSet(SymExpr left, SymExpr.CmpOp op, SymExpr right) {
        BigDecimal n;
        SymExpr.CmpOp effectiveOp;
        if (left instanceof SymExpr.Self && (n = asDecimalLiteral(right)) != null) {
            effectiveOp = op;
        } else if (right instanceof SymExpr.Self && (n = asDecimalLiteral(left)) != null) {
            effectiveOp = flip(op);
        } else {
            return null;
        }
        return switch (effectiveOp) {
            case GT -> DecIntervalSet.of(new DecInterval(n, false, null, false));
            case GE -> DecIntervalSet.of(new DecInterval(n, true, null, false));
            case LT -> DecIntervalSet.of(new DecInterval(null, false, n, false));
            case LE -> DecIntervalSet.of(new DecInterval(null, false, n, true));
            case EQ -> DecIntervalSet.of(new DecInterval(n, true, n, true));
            case NE -> new DecIntervalSet(List.of(
                    new DecInterval(null, false, n, false),
                    new DecInterval(n, false, null, false)));
        };
    }

    /** Inverse of {@link #toDecIntervalSet} for the canonical interval-set fragment. */
    private static SymExpr decIntervalSetToSymExpr(DecIntervalSet set) {
        if (set.isEmpty()) return SymExpr.bool(false);
        List<DecInterval> intervals = set.intervals();
        SymExpr result = decIntervalToSymExpr(intervals.get(0));
        for (int i = 1; i < intervals.size(); i++) {
            result = SymExpr.or(result, decIntervalToSymExpr(intervals.get(i)));
        }
        return result;
    }

    private static SymExpr decIntervalToSymExpr(DecInterval iv) {
        if (iv.lo() == null && iv.hi() == null) return SymExpr.bool(true);
        if (iv.lo() != null && iv.hi() != null && iv.lo().compareTo(iv.hi()) == 0) {
            return SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.EQ, SymExpr.dec(iv.lo()));
        }
        SymExpr lower = iv.lo() == null ? null : SymExpr.cmp(
                SymExpr.self(), iv.loIncl() ? SymExpr.CmpOp.GE : SymExpr.CmpOp.GT, SymExpr.dec(iv.lo()));
        SymExpr upper = iv.hi() == null ? null : SymExpr.cmp(
                SymExpr.self(), iv.hiIncl() ? SymExpr.CmpOp.LE : SymExpr.CmpOp.LT, SymExpr.dec(iv.hi()));
        if (lower == null) return upper;
        if (upper == null) return lower;
        return SymExpr.and(lower, upper);
    }

    /**
     * A single real interval with explicit open/closed endpoints. A {@code null}
     * endpoint is the corresponding infinity (never inclusive). Empty when the
     * bounds cross, or coincide with either side open (an open point holds nothing).
     */
    private record DecInterval(BigDecimal lo, boolean loIncl, BigDecimal hi, boolean hiIncl) {
        static final DecInterval FULL = new DecInterval(null, false, null, false);
        static final DecInterval EMPTY = new DecInterval(BigDecimal.ZERO, false, BigDecimal.ZERO, false);

        boolean isEmpty() {
            if (lo == null || hi == null) return false;
            int c = lo.compareTo(hi);
            if (c > 0) return true;
            if (c == 0) return !(loIncl && hiIncl);
            return false;
        }

        DecInterval intersect(DecInterval o) {
            BigDecimal nlo; boolean nloIncl;
            if (lo == null) { nlo = o.lo; nloIncl = o.loIncl; }
            else if (o.lo == null) { nlo = lo; nloIncl = loIncl; }
            else {
                int c = lo.compareTo(o.lo);
                if (c > 0) { nlo = lo; nloIncl = loIncl; }
                else if (c < 0) { nlo = o.lo; nloIncl = o.loIncl; }
                else { nlo = lo; nloIncl = loIncl && o.loIncl; }
            }
            BigDecimal nhi; boolean nhiIncl;
            if (hi == null) { nhi = o.hi; nhiIncl = o.hiIncl; }
            else if (o.hi == null) { nhi = hi; nhiIncl = hiIncl; }
            else {
                int c = hi.compareTo(o.hi);
                if (c < 0) { nhi = hi; nhiIncl = hiIncl; }
                else if (c > 0) { nhi = o.hi; nhiIncl = o.hiIncl; }
                else { nhi = hi; nhiIncl = hiIncl && o.hiIncl; }
            }
            DecInterval r = new DecInterval(nlo, nloIncl, nhi, nhiIncl);
            return r.isEmpty() ? EMPTY : r;
        }
    }

    /** A set of disjoint real intervals in canonical form (sorted, non-overlapping, non-touching-covering). */
    private record DecIntervalSet(List<DecInterval> intervals) {
        static final DecIntervalSet EMPTY = new DecIntervalSet(List.of());
        static final DecIntervalSet FULL = new DecIntervalSet(List.of(DecInterval.FULL));

        DecIntervalSet { intervals = List.copyOf(intervals); }

        boolean isEmpty() { return intervals.isEmpty(); }

        static DecIntervalSet of(DecInterval i) {
            return i.isEmpty() ? EMPTY : new DecIntervalSet(List.of(i));
        }

        DecIntervalSet intersect(DecIntervalSet other) {
            List<DecInterval> result = new ArrayList<>();
            for (DecInterval a : intervals) {
                for (DecInterval b : other.intervals) {
                    DecInterval m = a.intersect(b);
                    if (!m.isEmpty()) result.add(m);
                }
            }
            return canonicalize(result);
        }

        DecIntervalSet union(DecIntervalSet other) {
            List<DecInterval> all = new ArrayList<>(intervals.size() + other.intervals.size());
            all.addAll(intervals);
            all.addAll(other.intervals);
            return canonicalize(all);
        }

        /** Complement over the whole real line: the gaps between the intervals plus the ends. */
        DecIntervalSet complement() {
            if (intervals.isEmpty()) return FULL;
            List<DecInterval> result = new ArrayList<>(intervals.size() + 1);
            DecInterval first = intervals.get(0);
            if (first.lo() != null) {
                result.add(new DecInterval(null, false, first.lo(), !first.loIncl()));
            }
            for (int i = 1; i < intervals.size(); i++) {
                DecInterval prev = intervals.get(i - 1);
                DecInterval next = intervals.get(i);
                result.add(new DecInterval(prev.hi(), !prev.hiIncl(), next.lo(), !next.loIncl()));
            }
            DecInterval last = intervals.get(intervals.size() - 1);
            if (last.hi() != null) {
                result.add(new DecInterval(last.hi(), !last.hiIncl(), null, false));
            }
            List<DecInterval> nonEmpty = new ArrayList<>(result.size());
            for (DecInterval iv : result) if (!iv.isEmpty()) nonEmpty.add(iv);
            return nonEmpty.isEmpty() ? EMPTY : new DecIntervalSet(nonEmpty);
        }

        private static DecIntervalSet canonicalize(List<DecInterval> input) {
            List<DecInterval> nonEmpty = new ArrayList<>(input.size());
            for (DecInterval i : input) if (!i.isEmpty()) nonEmpty.add(i);
            if (nonEmpty.isEmpty()) return EMPTY;
            nonEmpty.sort(DecIntervalSet::compareLower);

            List<DecInterval> merged = new ArrayList<>();
            DecInterval current = nonEmpty.get(0);
            for (int i = 1; i < nonEmpty.size(); i++) {
                DecInterval next = nonEmpty.get(i);
                if (connects(current, next)) {
                    current = mergeUpper(current, next);
                } else {
                    merged.add(current);
                    current = next;
                }
            }
            merged.add(current);
            return new DecIntervalSet(merged);
        }

        /** Sort by lower endpoint: −∞ first, then value, then inclusive-before-exclusive. */
        private static int compareLower(DecInterval a, DecInterval b) {
            if (a.lo() == null && b.lo() == null) return 0;
            if (a.lo() == null) return -1;
            if (b.lo() == null) return 1;
            int c = a.lo().compareTo(b.lo());
            if (c != 0) return c;
            if (a.loIncl() == b.loIncl()) return 0;
            return a.loIncl() ? -1 : 1;  // inclusive lower starts earlier
        }

        /** Sorted so {@code cur.lo <= next.lo}: do they overlap or touch with the point covered? */
        private static boolean connects(DecInterval cur, DecInterval next) {
            if (cur.hi() == null) return true;                 // cur reaches +∞
            if (next.lo() == null) return true;                // shouldn't happen post-sort, but safe
            int c = cur.hi().compareTo(next.lo());
            if (c > 0) return true;                            // overlap
            if (c < 0) return false;                           // gap
            return cur.hiIncl() || next.loIncl();              // touch: covered iff either endpoint closed
        }

        /** Merge two connected intervals, taking the greater upper endpoint. */
        private static DecInterval mergeUpper(DecInterval cur, DecInterval next) {
            BigDecimal nhi; boolean nhiIncl;
            if (cur.hi() == null || next.hi() == null) { nhi = null; nhiIncl = false; }
            else {
                int c = cur.hi().compareTo(next.hi());
                if (c > 0) { nhi = cur.hi(); nhiIncl = cur.hiIncl(); }
                else if (c < 0) { nhi = next.hi(); nhiIncl = next.hiIncl(); }
                else { nhi = cur.hi(); nhiIncl = cur.hiIncl() || next.hiIncl(); }
            }
            return new DecInterval(cur.lo(), cur.loIncl(), nhi, nhiIncl);
        }
    }
}
