package sibarum.pontif.predicates;

import sibarum.pontif.core.symbolic.Sign;
import sibarum.pontif.core.symbolic.SignAnalysis;
import sibarum.pontif.core.symbolic.SymExpr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hybrid <b>linear-bound + sign</b> reasoning over the integer domain.
 * The sibling of {@link SignAnalysis}: where sign analysis answers "which
 * side of zero?", bound analysis answers "in what integer range?" — so it
 * decides thresholds sign analysis can't, like {@code [Int:@>1]}.
 *
 * <p>Comparisons are translation-invariant ({@code x > 1 ⟺ x - 1 > 0}), so
 * the engine normalizes to a linear form and reasons relative to it — no
 * privileged reference frame, no {@code >0}-vs-{@code >1} cliff. Pure
 * linear arithmetic can't bound <em>products</em> ({@code n*r}, {@code x*x}),
 * so those become opaque atoms whose range comes from {@link SignAnalysis}.
 * Neither tool subsumes the other; this is the hybrid.
 *
 * <h2>Soundness</h2>
 * Every atom bound is sound (a single-atom hypothesis like {@code x >= 1}
 * gives {@code [1, ∞)}; a sign gives the matching half-line), and
 * {@link Interval} arithmetic over-approximates. So when the <em>entire</em>
 * computed interval satisfies the goal, the real value set does too —
 * sound, never a false discharge. Incompleteness surfaces honestly as a
 * non-discharge.
 *
 * <h2>Integer-strictness</h2>
 * Bounds are integer-strict: {@code > c} becomes {@code [c+1, ∞)}, not
 * {@code (c, ∞)}. This folds in the "for integers, positive means
 * {@code >= 1}" bridge naturally — it's just how the half-lines are cut.
 * Sound only while the refinement domain is integer-only (the same gate
 * that keeps this out of the rational-calibrated {@link Sign} lattice).
 */
public final class BoundAnalysis {

    private BoundAnalysis() {}

    /**
     * The statically-known integer range of {@code expr} under
     * {@code hypotheses}. {@link Interval#all()} when nothing constrains it.
     * Exposed for reuse by call-site narrowing (the same query a future
     * inference consumer wants).
     */
    public static Interval bound(SymExpr expr, List<SymExpr> hypotheses) {
        return evaluate(LinearForm.normalize(expr), flatten(hypotheses));
    }

    /**
     * Can {@code goal} (a {@link SymExpr.Cmp}) be discharged from
     * {@code hypotheses}? Normalizes to {@code (subject - bound) OP 0},
     * bounds the difference, and checks whether the <em>whole</em> resulting
     * interval satisfies {@code OP 0}. Non-comparison goals are not decided
     * here ({@code false}).
     */
    public static boolean discharge(List<SymExpr> hypotheses, SymExpr goal) {
        if (!(goal instanceof SymExpr.Cmp(SymExpr subject, SymExpr.CmpOp op, SymExpr bound))) {
            return false;
        }
        LinearForm diff = LinearForm.normalize(subject).subtract(LinearForm.normalize(bound));
        Interval iv = evaluate(diff, flatten(hypotheses));
        // Contradictory hypotheses (empty range) entail anything.
        if (iv.isEmpty()) return true;
        return switch (op) {
            case GT -> iv.lo() > 0;
            case GE -> iv.lo() >= 0;
            case LT -> iv.hi() < 0;
            case LE -> iv.hi() <= 0;
            case EQ -> iv.lo() == 0 && iv.hi() == 0;
            case NE -> iv.lo() > 0 || iv.hi() < 0;
        };
    }

    /**
     * Expands top-level conjunctions so each conjunct is a separate fact.
     * A range refinement like {@code [Int:@>=1 & @<=4]} reaches the engine
     * as a single {@code And} hypothesis; flattening lets each bound
     * constrain its atom (and lets {@link SignAnalysis} see the parts too).
     */
    private static List<SymExpr> flatten(List<SymExpr> hypotheses) {
        List<SymExpr> flat = new ArrayList<>(hypotheses.size());
        for (SymExpr h : hypotheses) collectConjuncts(h, flat);
        return flat;
    }

    private static void collectConjuncts(SymExpr e, List<SymExpr> out) {
        if (e instanceof SymExpr.And(SymExpr l, SymExpr r)) {
            collectConjuncts(l, out);
            collectConjuncts(r, out);
        } else {
            out.add(e);
        }
    }

    // --- Interval evaluation of a linear form -------------------------------

    /** {@code c₀ + Σ cᵢ·interval(aᵢ)}, with interval scaling and addition. */
    private static Interval evaluate(LinearForm form, List<SymExpr> hypotheses) {
        Interval acc = Interval.point(form.constant());
        for (Map.Entry<SymExpr, Long> term : form.coeffs().entrySet()) {
            Interval atom = atomBound(term.getKey(), hypotheses);
            acc = acc.add(atom.scale(term.getValue()));
        }
        return acc;
    }

    /**
     * The range of a single atom: start at {@code (-∞, ∞)} and intersect
     * every sound constraint —
     * <ul>
     *   <li>each hypothesis whose normal form is exactly {@code 1·atom OP c}
     *       (integer-strict half-line), and</li>
     *   <li>the atom's {@link SignAnalysis sign} mapped to a half-line.
     *       This is what bounds opaque products: {@code n*r} with
     *       {@code n>0, r>=1} signs {@code POSITIVE → [1, ∞)}, and
     *       {@code x*x → NON_NEGATIVE → [0, ∞)}.</li>
     *   <li>for a product atom {@code l*r}, the interval-product of its
     *       factors' bounds. Sign reasoning gives only the product's
     *       <em>sign</em> ({@code x*y → [1, ∞)} from {@code x>=2, y>=3});
     *       multiplying the factor intervals recovers its <em>magnitude</em>
     *       ({@code [2,∞)·[3,∞) = [6, ∞)}). The two compose under
     *       intersection — for {@code x*x} over {@code [2,5]} the sign rule
     *       gives {@code [0,∞)} and the product gives {@code [4,25]}.</li>
     * </ul>
     */
    private static Interval atomBound(SymExpr atom, List<SymExpr> hypotheses) {
        Interval iv = Interval.all();
        for (SymExpr hyp : hypotheses) {
            Interval fromHyp = singleAtomConstraint(atom, hyp);
            if (fromHyp != null) iv = iv.intersect(fromHyp);
        }
        iv = iv.intersect(fromSign(SignAnalysis.computeSign(atom, hypotheses)));
        // An opaque product atom (a Mul both of whose factors are
        // non-constant — the only Mul shape normalize leaves as an atom) is
        // additionally bounded by multiplying its factors' bounds. Sound:
        // each factor bound is sound and Interval.multiply over-approximates;
        // intersecting can only tighten, never falsely discharge. Recursion
        // terminates on strictly-smaller subexpressions.
        if (atom instanceof SymExpr.Mul(SymExpr l, SymExpr r)) {
            Interval product = evaluate(LinearForm.normalize(l), hypotheses)
                    .multiply(evaluate(LinearForm.normalize(r), hypotheses));
            iv = iv.intersect(product);
        }
        return iv;
    }

    /**
     * If {@code hyp} is a comparison whose normal form is exactly
     * {@code 1·atom + c₀ OP 0} (i.e. {@code atom OP -c₀}), returns the
     * integer-strict half-line it bounds {@code atom} to; otherwise
     * {@code null}. Multi-atom hypotheses ({@code x + y > 0}) and
     * coefficients other than 1 don't bound a single atom and are skipped
     * (honest incompleteness — sign analysis may still contribute).
     */
    private static Interval singleAtomConstraint(SymExpr atom, SymExpr hyp) {
        if (!(hyp instanceof SymExpr.Cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r))) {
            return null;
        }
        LinearForm form = LinearForm.normalize(l).subtract(LinearForm.normalize(r));
        Map<SymExpr, Long> coeffs = form.coeffs();
        if (coeffs.size() != 1) return null;
        Map.Entry<SymExpr, Long> only = coeffs.entrySet().iterator().next();
        if (!only.getKey().equals(atom) || only.getValue() != 1L) return null;
        long c = -form.constant();  // atom + c₀ OP 0  ⟺  atom OP -c₀
        return switch (op) {
            case GT -> Interval.atLeast(c + 1);
            case GE -> Interval.atLeast(c);
            case LT -> Interval.atMost(c - 1);
            case LE -> Interval.atMost(c);
            case EQ -> Interval.point(c);
            case NE -> Interval.all();  // a hole isn't one interval
        };
    }

    /** Maps a sign to the integer half-line it guarantees. */
    private static Interval fromSign(Sign s) {
        return switch (s) {
            case POSITIVE -> Interval.atLeast(1);
            case NON_NEGATIVE -> Interval.atLeast(0);
            case ZERO -> Interval.point(0);
            case NEGATIVE -> Interval.atMost(-1);
            case NON_POSITIVE -> Interval.atMost(0);
            case TOP -> Interval.all();
            case BOTTOM -> Interval.empty();
        };
    }

    // --- Linear normal form -------------------------------------------------

    /**
     * An integer expression as {@code constant + Σ coeff·atom}. Atoms are
     * identified by structural {@link SymExpr} equality; the product of two
     * non-constant subexpressions is a single opaque atom (the whole
     * {@code Mul}), so {@link SignAnalysis} still sees its structure (e.g.
     * the square rule for {@code x*x}).
     *
     * <p>v1 caveat: {@code a*b} and {@code b*a} are distinct atoms;
     * commutative canonicalization is a cheap later refinement.
     */
    private record LinearForm(long constant, Map<SymExpr, Long> coeffs) {

        static LinearForm constant(long c) {
            return new LinearForm(c, Map.of());
        }

        static LinearForm atom(SymExpr a) {
            return new LinearForm(0, Map.of(a, 1L));
        }

        boolean isPureConstant() {
            return coeffs.isEmpty();
        }

        /** Merges two forms, summing coefficients per atom and dropping cancellations. */
        LinearForm plus(LinearForm other) {
            Map<SymExpr, Long> merged = new LinkedHashMap<>(coeffs);
            for (Map.Entry<SymExpr, Long> e : other.coeffs.entrySet()) {
                long sum = merged.getOrDefault(e.getKey(), 0L) + e.getValue();
                if (sum == 0) {
                    merged.remove(e.getKey());
                } else {
                    merged.put(e.getKey(), sum);
                }
            }
            return new LinearForm(constant + other.constant, merged);
        }

        /** Scales the constant and every coefficient by {@code k}. */
        LinearForm scale(long k) {
            if (k == 0) return constant(0);
            Map<SymExpr, Long> scaled = new LinkedHashMap<>(coeffs.size());
            for (Map.Entry<SymExpr, Long> e : coeffs.entrySet()) {
                scaled.put(e.getKey(), e.getValue() * k);
            }
            return new LinearForm(constant * k, scaled);
        }

        LinearForm subtract(LinearForm other) {
            return plus(other.scale(-1));
        }

        static LinearForm normalize(SymExpr e) {
            return switch (e) {
                case SymExpr.Lit(long v) -> constant(v);
                case SymExpr.Frac(long n, long d) -> d == 1 ? constant(n) : atom(e);
                case SymExpr.Var ignored -> atom(e);
                case SymExpr.Self ignored -> atom(e);
                case SymExpr.Add(SymExpr l, SymExpr r) -> normalize(l).plus(normalize(r));
                case SymExpr.Mul(SymExpr l, SymExpr r) -> {
                    LinearForm ln = normalize(l);
                    LinearForm rn = normalize(r);
                    if (ln.isPureConstant()) yield rn.scale(ln.constant);
                    if (rn.isPureConstant()) yield ln.scale(rn.constant);
                    yield atom(e);  // nonlinear product → opaque atom
                }
                // Pow, App, FieldAccess, Record, Bool, Cmp, And, Or, Lam, Case:
                // not part of the integer-linear fragment — opaque atom.
                default -> atom(e);
            };
        }
    }
}
