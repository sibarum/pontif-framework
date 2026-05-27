package sibarum.pontif.predicates;

/**
 * A closed integer interval {@code [lo, hi]} with explicit {@code ±∞}
 * endpoints, plus the <b>saturating arithmetic</b> the bound engine needs
 * ({@link #scale}, {@link #add}) on top of {@link #intersect}.
 *
 * <p>This is the value type returned by {@link BoundAnalysis#bound}: the
 * statically-known range of an integer expression. Over-approximation is
 * always toward the wider interval (saturate to {@code ±∞}, never wrap),
 * so any conclusion drawn from a whole-interval check is sound.
 *
 * <h2>Infinities</h2>
 * {@link #NEG_INF} / {@link #POS_INF} are the {@code long} sentinels for
 * {@code ∓∞}. Real bounds can never land exactly on them (the same
 * convention the satisfiability kernel's private interval already uses).
 * Arithmetic treats them as absorbing; overflow of finite endpoints
 * saturates to the appropriate infinity.
 *
 * <h2>Emptiness</h2>
 * {@code lo > hi} denotes the empty interval (an unsatisfiable / vacuous
 * range — e.g. produced by intersecting disjoint bounds, which means the
 * hypotheses are contradictory). Callers should check {@link #isEmpty}.
 *
 * <p><b>Note (follow-up):</b> {@link PredicateArithmetic} carries its own
 * private {@code Interval}/{@code IntervalSet} for satisfiability-over-a-
 * domain. That one models <em>sets</em> of integers (union/complement);
 * this one models a single range with arithmetic. They should eventually
 * merge — see {@code docs/TODO.md}.
 */
public record Interval(long lo, long hi) {

    public static final long NEG_INF = Long.MIN_VALUE;
    public static final long POS_INF = Long.MAX_VALUE;

    /** The whole integer line {@code (-∞, ∞)}. */
    public static Interval all() {
        return new Interval(NEG_INF, POS_INF);
    }

    /** The empty interval — no integer satisfies it. */
    public static Interval empty() {
        return new Interval(POS_INF, NEG_INF);
    }

    /** The single point {@code [k, k]}. */
    public static Interval point(long k) {
        return new Interval(k, k);
    }

    /** {@code [k, ∞)}. */
    public static Interval atLeast(long k) {
        return new Interval(k, POS_INF);
    }

    /** {@code (-∞, k]}. */
    public static Interval atMost(long k) {
        return new Interval(NEG_INF, k);
    }

    /** No integer falls in this interval ({@code lo > hi}). */
    public boolean isEmpty() {
        return lo > hi;
    }

    /**
     * The tightest interval contained in both. Disjoint inputs yield an
     * empty interval (sound: the conjunction of the two bounds holds for
     * no integer).
     */
    public Interval intersect(Interval other) {
        return new Interval(Math.max(lo, other.lo), Math.min(hi, other.hi));
    }

    /**
     * Scales by an integer coefficient. A negative coefficient flips the
     * endpoints (lower bound times a negative is the new upper bound).
     * {@code c == 0} collapses to {@code [0, 0]} regardless of {@code this}
     * (including empty — {@code 0·anything} contributes {@code 0}).
     */
    public Interval scale(long c) {
        if (c == 0) return point(0);
        if (isEmpty()) return this;
        long a = satMul(c, lo);
        long b = satMul(c, hi);
        return c > 0 ? new Interval(a, b) : new Interval(b, a);
    }

    /** Pointwise sum {@code [lo+other.lo, hi+other.hi]}, saturating. */
    public Interval add(Interval other) {
        if (isEmpty() || other.isEmpty()) return empty();
        return new Interval(satAdd(lo, other.lo), satAdd(hi, other.hi));
    }

    // --- Saturating long arithmetic over the ±∞ sentinels ------------------

    /**
     * {@code c · v}, saturating. {@code c} is a finite coefficient; {@code v}
     * may be an infinity sentinel. Overflow of two finite operands saturates
     * to the infinity matching the product's sign.
     */
    private static long satMul(long c, long v) {
        if (c == 0) return 0;
        if (v == POS_INF) return c > 0 ? POS_INF : NEG_INF;
        if (v == NEG_INF) return c > 0 ? NEG_INF : POS_INF;
        try {
            return Math.multiplyExact(c, v);
        } catch (ArithmeticException overflow) {
            return (c > 0) == (v > 0) ? POS_INF : NEG_INF;
        }
    }

    /**
     * {@code a + b}, saturating. Sound usage only ever sums same-side
     * endpoints (all lower bounds together, all upper bounds together), so
     * {@code +∞} and {@code -∞} never meet here; if they somehow did,
     * {@code +∞} wins (documented invariant, not expected to occur).
     */
    private static long satAdd(long a, long b) {
        if (a == POS_INF || b == POS_INF) return POS_INF;
        if (a == NEG_INF || b == NEG_INF) return NEG_INF;
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException overflow) {
            return a > 0 ? POS_INF : NEG_INF;
        }
    }
}
