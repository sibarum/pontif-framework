package sibarum.pontif.core.symbolic;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A real interval with explicit endpoint <b>strictness</b> — {@code [lo, hi]},
 * {@code (lo, hi]}, {@code [lo, hi)}, {@code (lo, hi)} — and {@code ±∞}
 * endpoints, over {@link BigDecimal} values. This is the domain-neutral range
 * type the unified {@link BoundAnalysis} engine reasons over: the same
 * arithmetic serves the integer and the {@code Decimal} domains, because the
 * only thing the integer domain adds is a <em>grid</em> (see
 * {@link BoundAnalysis} quantization), not different arithmetic.
 *
 * <p>Where the legacy integer {@code Interval} (in {@code pontif-predicates})
 * baked integer-strictness in by cutting
 * {@code >c} to {@code [c+1, ∞)} (closed, integer), this type keeps the
 * strictness explicit ({@code (c, ∞)}), so the same value serves a dense
 * domain where no such cut is valid ({@code 0.5} witnesses {@code >0} without
 * {@code >=1}).
 *
 * <h2>Infinities</h2>
 * A {@code null} lower value is {@code -∞}; a {@code null} upper value is
 * {@code +∞}. An infinite endpoint is never inclusive.
 *
 * <h2>Emptiness</h2>
 * An interval with finite endpoints is empty iff {@code lo > hi}, or
 * {@code lo == hi} with either endpoint exclusive (an open point holds no
 * value). {@link #EMPTY} is the canonical empty; {@link #isEmpty} recognizes
 * any empty shape.
 *
 * <h2>Soundness</h2>
 * Every operation over-approximates toward the wider interval, so any verdict
 * read off a whole-interval check is sound. {@link #multiply} additionally
 * defaults its result endpoints to <em>inclusive</em> (the wider, closed
 * choice): strictness recovery for products rides on the sign lattice
 * intersected in by the caller, never on the product itself.
 */
public record RealInterval(BigDecimal lo, boolean loIncl, BigDecimal hi, boolean hiIncl) {

    /** {@code (-∞, ∞)}. */
    public static final RealInterval ALL = new RealInterval(null, false, null, false);

    /** The canonical empty interval — no value satisfies it. */
    public static final RealInterval EMPTY =
            new RealInterval(BigDecimal.ZERO, false, BigDecimal.ZERO, false);

    public static RealInterval all() {
        return ALL;
    }

    public static RealInterval empty() {
        return EMPTY;
    }

    /** The single point {@code [k, k]}. */
    public static RealInterval point(BigDecimal k) {
        return new RealInterval(k, true, k, true);
    }

    /** {@code [k, ∞)} (inclusive) or {@code (k, ∞)} (exclusive). */
    public static RealInterval atLeast(BigDecimal k, boolean inclusive) {
        return new RealInterval(k, inclusive, null, false);
    }

    /** {@code (-∞, k]} (inclusive) or {@code (-∞, k)} (exclusive). */
    public static RealInterval atMost(BigDecimal k, boolean inclusive) {
        return new RealInterval(null, false, k, inclusive);
    }

    public boolean loIsInfinite() {
        return lo == null;
    }

    public boolean hiIsInfinite() {
        return hi == null;
    }

    /** No value falls in this interval. */
    public boolean isEmpty() {
        if (lo == null || hi == null) {
            return false;  // an infinite side always leaves room
        }
        int c = lo.compareTo(hi);
        return c > 0 || (c == 0 && !(loIncl && hiIncl));
    }

    /**
     * The tightest interval contained in both. Disjoint inputs yield an empty
     * interval (sound: the conjunction holds for no value). When the two share
     * an endpoint value the <em>exclusive</em> side wins (it is the tighter).
     */
    public RealInterval intersect(RealInterval o) {
        if (isEmpty() || o.isEmpty()) return EMPTY;
        // Lower bound: the larger value; on a tie, exclusive is tighter.
        BigDecimal newLo;
        boolean newLoIncl;
        if (lo == null) {
            newLo = o.lo;
            newLoIncl = o.loIncl;
        } else if (o.lo == null) {
            newLo = lo;
            newLoIncl = loIncl;
        } else {
            int c = lo.compareTo(o.lo);
            if (c > 0) { newLo = lo; newLoIncl = loIncl; }
            else if (c < 0) { newLo = o.lo; newLoIncl = o.loIncl; }
            else { newLo = lo; newLoIncl = loIncl && o.loIncl; }
        }
        // Upper bound: the smaller value; on a tie, exclusive is tighter.
        BigDecimal newHi;
        boolean newHiIncl;
        if (hi == null) {
            newHi = o.hi;
            newHiIncl = o.hiIncl;
        } else if (o.hi == null) {
            newHi = hi;
            newHiIncl = hiIncl;
        } else {
            int c = hi.compareTo(o.hi);
            if (c < 0) { newHi = hi; newHiIncl = hiIncl; }
            else if (c > 0) { newHi = o.hi; newHiIncl = o.hiIncl; }
            else { newHi = hi; newHiIncl = hiIncl && o.hiIncl; }
        }
        RealInterval r = new RealInterval(newLo, newLoIncl, newHi, newHiIncl);
        return r.isEmpty() ? EMPTY : r;
    }

    /**
     * Pointwise sum. A sum endpoint is inclusive iff <em>both</em> summands are
     * (an open bound infects — the infimum of two half-open lowers is not
     * attained). An infinite side stays infinite.
     */
    public RealInterval add(RealInterval o) {
        if (isEmpty() || o.isEmpty()) return EMPTY;
        BigDecimal newLo = (lo == null || o.lo == null) ? null : lo.add(o.lo);
        BigDecimal newHi = (hi == null || o.hi == null) ? null : hi.add(o.hi);
        return new RealInterval(
                newLo, newLo != null && loIncl && o.loIncl,
                newHi, newHi != null && hiIncl && o.hiIncl);
    }

    /**
     * Scales by a constant coefficient. A negative coefficient flips the
     * endpoints (the lower bound scaled by a negative becomes the new upper).
     * {@code k == 0} collapses to {@code [0, 0]}. Strictness is preserved — a
     * nonzero scale is a bijection, so an attained endpoint stays attained.
     */
    public RealInterval scale(BigDecimal k) {
        int sign = k.signum();
        if (sign == 0) return point(BigDecimal.ZERO);
        if (isEmpty()) return EMPTY;
        BigDecimal sLo = lo == null ? null : lo.multiply(k);
        BigDecimal sHi = hi == null ? null : hi.multiply(k);
        if (sign > 0) {
            return new RealInterval(sLo, lo != null && loIncl, sHi, hi != null && hiIncl);
        }
        // Negative: old-hi·k is the new lower, old-lo·k is the new upper.
        return new RealInterval(sHi, hi != null && hiIncl, sLo, lo != null && loIncl);
    }

    /**
     * The image of multiplying two ranges: the smallest interval containing
     * every product {@code a·b}. A product is bilinear, so its extremes over
     * the box occur at the four endpoint corners — the min/max over them
     * bounds every interior product (sound). Result endpoints are marked
     * inclusive (the conservative, wider choice); product-strictness recovery
     * is left to the sign lattice the caller intersects in.
     *
     * <p>The {@code 0·∞} corner is {@code 0} — forced, not conventional: the
     * slice at the {@code 0} endpoint contributes exactly {@code 0}, while the
     * unboundedness rides on the other corners (cf. the legacy integer
     * {@code Interval.multiply}).
     */
    public RealInterval multiply(RealInterval o) {
        if (isEmpty() || o.isEmpty()) return EMPTY;
        Val aLo = Val.lower(lo), aHi = Val.upper(hi);
        Val bLo = Val.lower(o.lo), bHi = Val.upper(o.hi);
        Val[] corners = {aLo.mul(bLo), aLo.mul(bHi), aHi.mul(bLo), aHi.mul(bHi)};
        Val min = corners[0], max = corners[0];
        for (Val c : corners) {
            if (c.compareTo(min) < 0) min = c;
            if (c.compareTo(max) > 0) max = c;
        }
        return new RealInterval(min.finiteOrNull(), !min.infinite(),
                max.finiteOrNull(), !max.infinite());
    }

    /**
     * A signed value on the extended real line: a finite {@link BigDecimal}, or
     * {@code ±∞}. Used only inside {@link #multiply} to take corner products
     * with the {@code 0·∞ = 0} rule and to order the corners.
     */
    private record Val(BigDecimal v, int inf) {  // inf ∈ {-1, 0, +1}; v used iff inf == 0

        static Val lower(BigDecimal b) {
            return b == null ? new Val(null, -1) : new Val(b, 0);
        }

        static Val upper(BigDecimal b) {
            return b == null ? new Val(null, 1) : new Val(b, 0);
        }

        boolean infinite() {
            return inf != 0;
        }

        BigDecimal finiteOrNull() {
            return inf == 0 ? v : null;
        }

        int sign() {
            return inf != 0 ? inf : v.signum();
        }

        Val mul(Val o) {
            if (sign() == 0 || o.sign() == 0) {
                return new Val(BigDecimal.ZERO, 0);  // 0·anything = 0 (incl. 0·∞)
            }
            int s = sign() * o.sign();
            if (infinite() || o.infinite()) {
                return new Val(null, s);
            }
            return new Val(v.multiply(o.v), 0);
        }

        int compareTo(Val o) {
            if (inf != o.inf) return Integer.compare(inf, o.inf);
            if (inf != 0) return 0;
            return v.compareTo(o.v);
        }
    }

    /**
     * Project onto the integer grid: the tightest CLOSED integer-aligned
     * interval with the same integer content. An exclusive bound moves inward
     * to the next integer ({@code (0,∞)} ⇒ {@code [1,∞)}), an inclusive bound
     * rounds inward to an integer ({@code (-∞, 2.5]} ⇒ {@code (-∞, 2]}). Empties
     * when no integer falls inside ({@code (0,1)} ⇒ ∅). This is the one
     * dense-invalid move, sound only over a discrete domain — the single place
     * integer semantics live (cf. {@link BoundAnalysis}, which delegates here).
     */
    public RealInterval quantizeToInt() {
        if (isEmpty()) return EMPTY;
        BigDecimal newLo = lo == null ? null
                : (loIncl ? ceil(lo) : floor(lo).add(BigDecimal.ONE));
        BigDecimal newHi = hi == null ? null
                : (hiIncl ? floor(hi) : ceil(hi).subtract(BigDecimal.ONE));
        RealInterval r = new RealInterval(newLo, newLo != null, newHi, newHi != null);
        return r.isEmpty() ? EMPTY : r;
    }

    /** {@code ⌊v⌋} as a {@link BigDecimal}. */
    public static BigDecimal floor(BigDecimal v) {
        return v.setScale(0, RoundingMode.FLOOR);
    }

    /** {@code ⌈v⌉} as a {@link BigDecimal}. */
    public static BigDecimal ceil(BigDecimal v) {
        return v.setScale(0, RoundingMode.CEILING);
    }
}
