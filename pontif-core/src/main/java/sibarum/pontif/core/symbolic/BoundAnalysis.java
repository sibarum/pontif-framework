package sibarum.pontif.core.symbolic;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one linear-bound + sign reasoning kernel, unified across the integer and
 * {@code Decimal} domains and shared by every layer that reasons about
 * refinements. It answers two questions over the same machinery:
 * <ul>
 *   <li>{@link #discharge} — can a comparison goal be proved from a set of
 *       hypotheses? (the receipt-graph return gate; {@link Refinements}' numeric
 *       implication leaf)</li>
 *   <li>{@link #bound} — what range does an expression occupy? (integer
 *       call-site narrowing, via the {@code pontif-predicates} projection)</li>
 * </ul>
 *
 * <p>It lives in {@code pontif-core} deliberately: it is the single home for
 * numeric-linear reasoning, and core is the only layer both {@link Refinements}
 * (the refinement-proof kernel) and the higher receipt / predicate layers can
 * depend on. Enhancing the reasoning happens here and nowhere else.
 *
 * <p><b>One engine, two domains.</b> The arithmetic — normalize to a linear
 * form, evaluate it as a {@link RealInterval} under the hypotheses (add, scale,
 * multiply, intersect), read the verdict off the result — is domain-neutral,
 * sound over any ordered field. The <em>only</em> thing the integer domain adds
 * is a grid, applied in a single guarded step, {@link #quantize}: an open
 * integer bound {@code >c} snaps to the closed {@code >=c+1}, and
 * {@code POSITIVE} snaps to {@code >=1}. For {@code Decimal} that step is the
 * identity — {@code 0.5} witnesses {@code >0} without {@code >=1}, so no cut is
 * valid. That single difference is what makes {@code [Int:@>0] → [Int:@>=1]}
 * provable while {@code [Decimal:@>0] → [Decimal:@>=1]} is correctly rejected:
 * same arithmetic, different grid.
 *
 * <p>Comparisons are translation-invariant ({@code x > 1 ⟺ x - 1 > 0}), so the
 * engine normalizes to a linear form and reasons relative to it. Pure linear
 * arithmetic can't bound <em>products</em>, so those become opaque atoms whose
 * range comes from {@link SignAnalysis} intersected with the interval-product
 * of the factors' bounds. When a product's sign is still undecided
 * ({@code x*(x-1)}), the engine falls back to an internal sign-chart case-split
 * over the factors' integer roots — a move that partitions {@code ℤ}, sound
 * only in the discrete domain and thus gated to {@link Domain#INT}.
 *
 * <h2>Soundness</h2>
 * Every atom bound is sound and {@link RealInterval} arithmetic
 * over-approximates, so when the whole computed interval satisfies the goal the
 * real value set does too — never a false discharge. The one dense-invalid
 * move, the integer-strict cut, lives exclusively inside {@link #quantize}
 * under a {@code Domain.INT} guard; nothing else consults the domain.
 */
public final class BoundAnalysis {

    private BoundAnalysis() {}

    /** The numeric domain a query runs in — the sole axis the engine branches on. */
    public enum Domain {
        /** Discrete: bounds snap to the integer grid ({@code >c ⟹ >=c+1}). */
        INT,
        /** Dense: no grid; open bounds stay open. */
        DECIMAL
    }

    /**
     * The statically-known range of {@code expr} under {@code hypotheses} in
     * {@code domain}. {@link RealInterval#all()} when nothing constrains it.
     * The {@code pontif-predicates} layer projects the {@link Domain#INT}
     * result to its legacy long-based interval for call-site narrowing.
     */
    public static RealInterval bound(Domain domain, SymExpr expr, List<SymExpr> hypotheses) {
        return evaluate(LinearForm.normalize(expr), flatten(hypotheses), domain);
    }

    /**
     * Can {@code goal} be discharged from {@code hypotheses} over the integers?
     * The {@link Domain#INT} specialization, kept as the established two-arg
     * entry point for integer callers.
     */
    public static boolean discharge(List<SymExpr> hypotheses, SymExpr goal) {
        return discharge(Domain.INT, hypotheses, goal);
    }

    /**
     * Can {@code goal} (a {@link SymExpr.Cmp}) be discharged from
     * {@code hypotheses} in {@code domain}? Normalizes to
     * {@code (subject - bound) OP 0}, bounds the difference as a
     * {@link RealInterval}, and checks whether the <em>whole</em> interval
     * satisfies {@code OP 0}. Disjunctive / conjunctive goals are decomposed;
     * any other non-comparison goal is not decided here ({@code false}).
     */
    public static boolean discharge(Domain domain, List<SymExpr> hypotheses, SymExpr goal) {
        if (goal instanceof SymExpr.Or(SymExpr orL, SymExpr orR)) {
            return discharge(domain, hypotheses, orL) || discharge(domain, hypotheses, orR);
        }
        if (goal instanceof SymExpr.And(SymExpr andL, SymExpr andR)) {
            return discharge(domain, hypotheses, andL) && discharge(domain, hypotheses, andR);
        }
        if (!(goal instanceof SymExpr.Cmp(SymExpr subject, SymExpr.CmpOp op, SymExpr bound))) {
            return false;
        }
        return dischargeCmp(domain, hypotheses, subject, op, bound)
                || (domain == Domain.INT && dischargeViaSignChart(hypotheses, subject, op, bound));
    }

    /** The direct linear+sign interval verdict for a single comparison goal. */
    private static boolean dischargeCmp(
            Domain domain, List<SymExpr> hypotheses, SymExpr subject, SymExpr.CmpOp op, SymExpr bound) {
        LinearForm diff = LinearForm.normalize(subject).subtract(LinearForm.normalize(bound));
        RealInterval iv = evaluate(diff, flatten(hypotheses), domain);
        // Contradictory hypotheses (empty range) entail anything.
        if (iv.isEmpty()) return true;
        BigDecimal lo = iv.lo();
        BigDecimal hi = iv.hi();
        return switch (op) {
            case GT -> lo != null && (lo.signum() > 0 || (lo.signum() == 0 && !iv.loIncl()));
            case GE -> lo != null && lo.signum() >= 0;
            case LT -> hi != null && (hi.signum() < 0 || (hi.signum() == 0 && !iv.hiIncl()));
            case LE -> hi != null && hi.signum() <= 0;
            case EQ -> lo != null && hi != null
                    && lo.signum() == 0 && hi.signum() == 0 && iv.loIncl() && iv.hiIncl();
            case NE -> (lo != null && (lo.signum() > 0 || (lo.signum() == 0 && !iv.loIncl())))
                    || (hi != null && (hi.signum() < 0 || (hi.signum() == 0 && !iv.hiIncl())));
        };
    }

    /**
     * The case-split the engine used to delegate to a user-supplied proof: the
     * sign of a product like {@code x*(x-1)} is undecided by sign analysis, but
     * pinned once the shared variable's sign is fixed. Split that variable at
     * the factors' integer roots into an exhaustive partition of {@code ℤ}
     * (sound only because the domain is discrete), and discharge in every cell.
     * Generate-and-check: {@code true} only if <em>every</em> cell of an
     * exhaustive cover discharges. One level deep, so it always terminates.
     */
    private static boolean dischargeViaSignChart(
            List<SymExpr> hypotheses, SymExpr subject, SymExpr.CmpOp op, SymExpr bound) {
        LinearForm diff = LinearForm.normalize(subject).subtract(LinearForm.normalize(bound));
        List<List<SymExpr>> cells = planSignChart(diff);
        if (cells == null) return false;
        for (List<SymExpr> cellGuards : cells) {
            List<SymExpr> cellHyps = new ArrayList<>(hypotheses);
            cellHyps.addAll(cellGuards);
            if (!dischargeCmp(Domain.INT, cellHyps, subject, op, bound)) return false;
        }
        return true;
    }

    /**
     * The exhaustive integer cells to split into, or {@code null} when the goal
     * offers no single-variable product to sign-chart. Cells are guard lists
     * partitioning {@code ℤ} at the product factors' integer roots.
     */
    private static List<List<SymExpr>> planSignChart(LinearForm diff) {
        SymExpr var = null;
        java.util.TreeSet<Long> cuts = new java.util.TreeSet<>();
        boolean foundProduct = false;
        for (SymExpr atom : diff.coeffs().keySet()) {
            if (!(atom instanceof SymExpr.Mul(SymExpr l, SymExpr r))) continue;
            foundProduct = true;
            for (SymExpr factor : List.of(l, r)) {
                Factor fi = factorOf(factor);
                if (fi == null) return null;        // a factor we can't sign-chart
                if (fi.constant()) continue;        // contributes no cut/variable
                if (var == null) var = fi.var();
                else if (!var.equals(fi.var())) return null;  // multi-variable → bail
                cuts.add(fi.cut());
            }
        }
        if (!foundProduct || var == null || cuts.isEmpty()) return null;

        List<Long> sorted = new ArrayList<>(cuts);
        List<List<SymExpr>> cells = new ArrayList<>();
        cells.add(List.of(cellCmp(var, SymExpr.CmpOp.LE, sorted.get(0))));
        for (int i = 1; i < sorted.size(); i++) {
            cells.add(List.of(
                    cellCmp(var, SymExpr.CmpOp.GE, sorted.get(i - 1) + 1),
                    cellCmp(var, SymExpr.CmpOp.LE, sorted.get(i))));
        }
        cells.add(List.of(cellCmp(var, SymExpr.CmpOp.GE, sorted.get(sorted.size() - 1) + 1)));
        return cells;
    }

    /**
     * A product factor as either a constant or {@code a·v + b} over a single
     * variable {@code v} (with sign-flip integer root {@code floor(-b/a)}), or
     * {@code null} when it is multi-variable or itself non-linear.
     */
    private record Factor(SymExpr var, long cut, boolean constant) {}

    private static Factor factorOf(SymExpr factor) {
        LinearForm f = LinearForm.normalize(factor);
        Map<SymExpr, BigDecimal> coeffs = f.coeffs();
        if (coeffs.isEmpty()) return new Factor(null, 0, true);
        if (coeffs.size() != 1) return null;
        Map.Entry<SymExpr, BigDecimal> only = coeffs.entrySet().iterator().next();
        SymExpr v = only.getKey();
        if (!(v instanceof SymExpr.Var) && !(v instanceof SymExpr.Self)) return null;
        BigDecimal a = only.getValue();
        if (a.signum() == 0) return null;
        BigDecimal root = f.constant().negate().divide(a, java.math.MathContext.DECIMAL64);
        return new Factor(v, RealInterval.floor(root).longValueExact(), false);
    }

    private static SymExpr cellCmp(SymExpr var, SymExpr.CmpOp op, long c) {
        return SymExpr.cmp(var, op, SymExpr.lit(c));
    }

    /** Expands top-level conjunctions so each conjunct is a separate fact. */
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
    private static RealInterval evaluate(LinearForm form, List<SymExpr> hypotheses, Domain domain) {
        RealInterval acc = RealInterval.point(form.constant());
        for (Map.Entry<SymExpr, BigDecimal> term : form.coeffs().entrySet()) {
            RealInterval atom = atomBound(term.getKey(), hypotheses, domain);
            acc = acc.add(atom.scale(term.getValue()));
        }
        return acc;
    }

    /**
     * The range of a single atom: start at {@code (-∞, ∞)} and intersect every
     * sound constraint — each hypothesis whose normal form is exactly
     * {@code 1·atom OP c} (a domain-quantized half-line), the atom's
     * {@link SignAnalysis sign} (also domain-quantized), and, for a product
     * atom, the interval-product of its factors' bounds.
     */
    private static RealInterval atomBound(SymExpr atom, List<SymExpr> hypotheses, Domain domain) {
        RealInterval iv = RealInterval.all();
        for (SymExpr hyp : hypotheses) {
            RealInterval fromHyp = singleAtomConstraint(atom, hyp, domain);
            if (fromHyp != null) iv = iv.intersect(fromHyp);
        }
        iv = iv.intersect(fromSign(SignAnalysis.computeSign(atom, hypotheses), domain));
        if (atom instanceof SymExpr.Mul(SymExpr l, SymExpr r)) {
            RealInterval product = evaluate(LinearForm.normalize(l), hypotheses, domain)
                    .multiply(evaluate(LinearForm.normalize(r), hypotheses, domain));
            iv = iv.intersect(product);
        }
        return iv;
    }

    /**
     * If {@code hyp} is a comparison whose normal form is exactly
     * {@code 1·atom + c₀ OP 0}, returns the domain-quantized half-line it bounds
     * {@code atom} to; otherwise {@code null}. Multi-atom hypotheses and
     * coefficients other than 1 don't bound a single atom and are skipped.
     */
    private static RealInterval singleAtomConstraint(SymExpr atom, SymExpr hyp, Domain domain) {
        if (!(hyp instanceof SymExpr.Cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r))) {
            return null;
        }
        LinearForm form = LinearForm.normalize(l).subtract(LinearForm.normalize(r));
        Map<SymExpr, BigDecimal> coeffs = form.coeffs();
        if (coeffs.size() != 1) return null;
        Map.Entry<SymExpr, BigDecimal> only = coeffs.entrySet().iterator().next();
        if (!only.getKey().equals(atom) || only.getValue().compareTo(BigDecimal.ONE) != 0) {
            return null;
        }
        BigDecimal c = form.constant().negate();  // atom + c₀ OP 0  ⟺  atom OP -c₀
        RealInterval halfLine = switch (op) {
            case GT -> RealInterval.atLeast(c, false);
            case GE -> RealInterval.atLeast(c, true);
            case LT -> RealInterval.atMost(c, false);
            case LE -> RealInterval.atMost(c, true);
            case EQ -> RealInterval.point(c);
            case NE -> RealInterval.all();  // a hole isn't one interval
        };
        return quantize(halfLine, domain);
    }

    /** Maps a sign to the (domain-quantized) half-line it guarantees. */
    private static RealInterval fromSign(Sign s, Domain domain) {
        RealInterval halfLine = switch (s) {
            case POSITIVE -> RealInterval.atLeast(BigDecimal.ZERO, false);
            case NON_NEGATIVE -> RealInterval.atLeast(BigDecimal.ZERO, true);
            case ZERO -> RealInterval.point(BigDecimal.ZERO);
            case NEGATIVE -> RealInterval.atMost(BigDecimal.ZERO, false);
            case NON_POSITIVE -> RealInterval.atMost(BigDecimal.ZERO, true);
            case TOP -> RealInterval.all();
            case BOTTOM -> RealInterval.empty();
        };
        return quantize(halfLine, domain);
    }

    /**
     * The <b>sole domain-specific step.</b> In {@link Domain#INT} an atom is
     * integer-valued, so an open bound tightens to the next integer inside it
     * ({@code >c ⟹ >=⌊c⌋+1}, {@code <c ⟹ <=⌈c⌉-1}) and an inclusive bound
     * snaps to the grid ({@code >=c ⟹ >=⌈c⌉}); the result is always closed.
     * This folds in "for integers, positive means {@code >=1}". In
     * {@link Domain#DECIMAL} the domain is dense and this is the identity — the
     * one line that must never apply an integer-strict cut to a Decimal bound.
     */
    private static RealInterval quantize(RealInterval iv, Domain domain) {
        return domain == Domain.INT ? iv.quantizeToInt() : iv;
    }

    // --- Linear normal form -------------------------------------------------

    /**
     * A numeric expression as {@code constant + Σ coeff·atom} over
     * {@link BigDecimal} — exact for both integer and decimal literals (the
     * only constants that reach the kernel; division / mod / pow are hoisted
     * opaque upstream). Atoms are identified by structural {@link SymExpr}
     * equality; the product of two non-constant subexpressions is one opaque
     * atom, so {@link SignAnalysis} still sees its structure.
     *
     * <p>v1 caveat: {@code a*b} and {@code b*a} are distinct atoms;
     * commutative canonicalization is a cheap later refinement.
     */
    private record LinearForm(BigDecimal constant, Map<SymExpr, BigDecimal> coeffs) {

        static LinearForm constant(BigDecimal c) {
            return new LinearForm(c, Map.of());
        }

        static LinearForm atom(SymExpr a) {
            return new LinearForm(BigDecimal.ZERO, Map.of(a, BigDecimal.ONE));
        }

        boolean isPureConstant() {
            return coeffs.isEmpty();
        }

        LinearForm plus(LinearForm other) {
            Map<SymExpr, BigDecimal> merged = new LinkedHashMap<>(coeffs);
            for (Map.Entry<SymExpr, BigDecimal> e : other.coeffs.entrySet()) {
                BigDecimal sum = merged.getOrDefault(e.getKey(), BigDecimal.ZERO).add(e.getValue());
                if (sum.signum() == 0) {
                    merged.remove(e.getKey());
                } else {
                    merged.put(e.getKey(), sum);
                }
            }
            return new LinearForm(constant.add(other.constant), merged);
        }

        LinearForm scale(BigDecimal k) {
            if (k.signum() == 0) return constant(BigDecimal.ZERO);
            Map<SymExpr, BigDecimal> scaled = new LinkedHashMap<>(coeffs.size());
            for (Map.Entry<SymExpr, BigDecimal> e : coeffs.entrySet()) {
                scaled.put(e.getKey(), e.getValue().multiply(k));
            }
            return new LinearForm(constant.multiply(k), scaled);
        }

        LinearForm subtract(LinearForm other) {
            return plus(other.scale(BigDecimal.valueOf(-1)));
        }

        static LinearForm normalize(SymExpr e) {
            return switch (e) {
                case SymExpr.Lit(long v) -> constant(BigDecimal.valueOf(v));
                case SymExpr.Frac(long n, long d) -> d == 1 ? constant(BigDecimal.valueOf(n)) : atom(e);
                case SymExpr.Dec d -> constant(d.value());
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
                default -> atom(e);
            };
        }
    }
}
