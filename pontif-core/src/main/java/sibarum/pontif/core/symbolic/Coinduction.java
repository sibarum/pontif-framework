package sibarum.pontif.core.symbolic;

import java.util.HashSet;
import java.util.Set;

/**
 * The termination discipline for sort-level relations over recursive types.
 *
 * <p>Equi-recursive sorts (a type <em>is</em> its unfolding) make a structural
 * relation — equality, subsumption ({@code imply}) — a <b>greatest</b> fixed
 * point: two recursive types are related unless some finite obligation refutes
 * it. The standard algorithm (Amadio–Cardelli / Brandt–Henglein) computes
 * membership by assuming the goal and discharging the obligations it generates:
 * descending into a named sort records the pair (or name) as an assumption, and
 * revisiting it <em>assumes it holds</em> and stops, breaking the otherwise
 * infinite regress. Every non-back-edge obligation is still checked normally,
 * so the assumption only breaks the loop — it never rescues a relation a finite
 * obligation refutes.
 *
 * <p>Two carriers, both immutable / copy-on-extend so an assumption entered on
 * one branch of a union or function never leaks to a sibling branch:
 * <ul>
 *   <li>{@link Assumed} — a set of <em>ordered</em> name pairs, for directional
 *       relations like subsumption ({@code A <: B} assumed does not assume
 *       {@code B <: A}). Use {@link NamePair#unordered} for symmetric
 *       relations like equality.</li>
 *   <li>{@link Seen} — a set of single names, for one-directional walks (e.g.
 *       projecting through a recursive field in narrowing).</li>
 * </ul>
 *
 * <p>Value-directed relations ({@code satisfies(value, sort)}) do <em>not</em>
 * need this: descent is bounded by the finite runtime value, not by the
 * (possibly cyclic) type graph.
 */
public final class Coinduction {

    private Coinduction() {}

    /** An ordered pair of sort names; {@link #unordered} normalizes for symmetric use. */
    public record NamePair(String a, String b) {
        public static NamePair unordered(String x, String y) {
            return x.compareTo(y) <= 0 ? new NamePair(x, y) : new NamePair(y, x);
        }
    }

    /** Two-sided assumption set for directional relations (subsumption, equality). */
    public record Assumed(Set<NamePair> pairs) {

        public Assumed {
            pairs = Set.copyOf(pairs);
        }

        public static Assumed empty() {
            return new Assumed(Set.of());
        }

        /** Is the (ordered) pair already assumed on this path? */
        public boolean holds(String a, String b) {
            return pairs.contains(new NamePair(a, b));
        }

        /** A copy with {@code (a, b)} added — the original is untouched. */
        public Assumed assuming(String a, String b) {
            Set<NamePair> next = new HashSet<>(pairs);
            next.add(new NamePair(a, b));
            return new Assumed(next);
        }
    }

    /** One-sided visited set for single-direction walks. */
    public record Seen(Set<String> names) {

        public Seen {
            names = Set.copyOf(names);
        }

        public static Seen empty() {
            return new Seen(Set.of());
        }

        public boolean has(String name) {
            return names.contains(name);
        }

        /** A copy with {@code name} added — the original is untouched. */
        public Seen with(String name) {
            Set<String> next = new HashSet<>(names);
            next.add(name);
            return new Seen(next);
        }
    }
}
