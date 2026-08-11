package sibarum.pontif.core.symbolic;

import java.util.ArrayList;
import java.util.List;

/**
 * A finite union of disjoint {@link RealInterval}s, in canonical form (sorted by
 * lower endpoint; no empty, overlapping, or touch-and-covering members). The
 * set-level companion to {@link RealInterval}: union, intersection, and
 * complement over the whole real line, plus {@link #quantizeInt} for the integer
 * grid.
 *
 * <p>This is the single interval-set core the type checker's coverage reasoning
 * (match totality, the {@code _}-arm complement, refinement satisfiability) runs
 * on, alongside {@link BoundAnalysis}'s single-interval bound reasoning — both
 * built on {@link RealInterval}, so a new ordered domain is a projection choice
 * ({@link #quantizeInt} vs. the dense identity), never a reimplementation.
 *
 * <h2>Domains</h2>
 * The set algebra itself is domain-neutral (dense). Integer semantics enter only
 * at the leaves: {@link #quantizeInt} snaps every member to the integer grid, so
 * a dense gap like {@code (0,1)} — non-empty over the reals, empty over the
 * integers — is recognized as empty after quantization. A {@code Decimal} query
 * skips quantization and reads the dense set directly.
 */
public record RealIntervalSet(List<RealInterval> intervals) {

    public static final RealIntervalSet EMPTY = new RealIntervalSet(List.of());
    public static final RealIntervalSet FULL = new RealIntervalSet(List.of(RealInterval.ALL));

    public RealIntervalSet {
        intervals = List.copyOf(intervals);
    }

    public static RealIntervalSet of(RealInterval iv) {
        return iv.isEmpty() ? EMPTY : new RealIntervalSet(List.of(iv));
    }

    /** Canonicalizes an arbitrary bag of intervals into a disjoint sorted set. */
    public static RealIntervalSet ofAll(List<RealInterval> ivs) {
        return canonicalize(ivs);
    }

    /** No value (over the reals) falls in the set. */
    public boolean isEmpty() {
        return intervals.isEmpty();
    }

    public RealIntervalSet union(RealIntervalSet other) {
        List<RealInterval> all = new ArrayList<>(intervals.size() + other.intervals.size());
        all.addAll(intervals);
        all.addAll(other.intervals);
        return canonicalize(all);
    }

    public RealIntervalSet intersect(RealIntervalSet other) {
        List<RealInterval> result = new ArrayList<>();
        for (RealInterval a : intervals) {
            for (RealInterval b : other.intervals) {
                RealInterval m = a.intersect(b);
                if (!m.isEmpty()) result.add(m);
            }
        }
        return canonicalize(result);
    }

    /** Complement over the whole real line: the gaps between members plus the ends. */
    public RealIntervalSet complement() {
        if (intervals.isEmpty()) return FULL;
        List<RealInterval> result = new ArrayList<>(intervals.size() + 1);
        RealInterval first = intervals.get(0);
        if (first.lo() != null) {
            result.add(new RealInterval(null, false, first.lo(), !first.loIncl()));
        }
        for (int i = 1; i < intervals.size(); i++) {
            RealInterval prev = intervals.get(i - 1);
            RealInterval next = intervals.get(i);
            result.add(new RealInterval(prev.hi(), !prev.hiIncl(), next.lo(), !next.loIncl()));
        }
        RealInterval last = intervals.get(intervals.size() - 1);
        if (last.hi() != null) {
            result.add(new RealInterval(last.hi(), !last.hiIncl(), null, false));
        }
        List<RealInterval> nonEmpty = new ArrayList<>(result.size());
        for (RealInterval iv : result) if (!iv.isEmpty()) nonEmpty.add(iv);
        return nonEmpty.isEmpty() ? EMPTY : new RealIntervalSet(nonEmpty);
    }

    /** The set projected onto the integer grid — each member {@link RealInterval#quantizeToInt}d. */
    public RealIntervalSet quantizeInt() {
        List<RealInterval> q = new ArrayList<>(intervals.size());
        for (RealInterval iv : intervals) {
            RealInterval qi = iv.quantizeToInt();
            if (!qi.isEmpty()) q.add(qi);
        }
        return canonicalize(q);
    }

    private static RealIntervalSet canonicalize(List<RealInterval> input) {
        List<RealInterval> nonEmpty = new ArrayList<>(input.size());
        for (RealInterval i : input) if (!i.isEmpty()) nonEmpty.add(i);
        if (nonEmpty.isEmpty()) return EMPTY;
        nonEmpty.sort(RealIntervalSet::compareLower);

        List<RealInterval> merged = new ArrayList<>();
        RealInterval current = nonEmpty.get(0);
        for (int i = 1; i < nonEmpty.size(); i++) {
            RealInterval next = nonEmpty.get(i);
            if (connects(current, next)) {
                current = mergeUpper(current, next);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return new RealIntervalSet(merged);
    }

    /** Sort by lower endpoint: −∞ first, then value, then inclusive-before-exclusive. */
    private static int compareLower(RealInterval a, RealInterval b) {
        if (a.lo() == null && b.lo() == null) return 0;
        if (a.lo() == null) return -1;
        if (b.lo() == null) return 1;
        int c = a.lo().compareTo(b.lo());
        if (c != 0) return c;
        if (a.loIncl() == b.loIncl()) return 0;
        return a.loIncl() ? -1 : 1;  // inclusive lower starts at-or-before exclusive
    }

    /** Sorted so {@code cur.lo <= next.lo}: overlap, or touch with the shared point covered? */
    private static boolean connects(RealInterval cur, RealInterval next) {
        if (cur.hi() == null) return true;      // cur reaches +∞
        if (next.lo() == null) return true;     // defensive; next.lo is non-null post-sort
        int c = cur.hi().compareTo(next.lo());
        if (c > 0) return true;                 // overlap
        if (c < 0) return false;                // gap
        return cur.hiIncl() || next.loIncl();   // touch: covered iff either endpoint closed
    }

    /** Merge two connected intervals, taking the greater upper endpoint. */
    private static RealInterval mergeUpper(RealInterval cur, RealInterval next) {
        java.math.BigDecimal nhi;
        boolean nhiIncl;
        if (cur.hi() == null || next.hi() == null) {
            nhi = null;
            nhiIncl = false;
        } else {
            int c = cur.hi().compareTo(next.hi());
            if (c > 0) { nhi = cur.hi(); nhiIncl = cur.hiIncl(); }
            else if (c < 0) { nhi = next.hi(); nhiIncl = next.hiIncl(); }
            else { nhi = cur.hi(); nhiIncl = cur.hiIncl() || next.hiIncl(); }
        }
        return new RealInterval(cur.lo(), cur.loIncl(), nhi, nhiIncl);
    }
}
