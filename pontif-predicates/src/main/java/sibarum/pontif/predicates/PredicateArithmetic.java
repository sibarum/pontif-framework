package sibarum.pontif.predicates;

import sibarum.pontif.core.symbolic.RealInterval;
import sibarum.pontif.core.symbolic.RealIntervalSet;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;

import java.math.BigDecimal;

/**
 * Predicate arithmetic kernel for Pontif.
 *
 * <p>Operations over symbolic predicates ({@link SymExpr}) parameterized by a
 * sort domain. Used by:
 * <ul>
 *   <li>Dispatch overload-overlap checking — is {@code pred_A ∧ pred_B}
 *       unsatisfiable?
 *   <li>Match totality proving — is {@code sort ∧ ¬(union of arms)}
 *       unsatisfiable?
 *   <li>Match {@code _} default arm desugar — complement of explicit arms over
 *       the scrutinee's sort.
 * </ul>
 *
 * <p>All three reduce to {@link #satisfiable(SymExpr, Sort) satisfiability} and
 * {@link #complement(SymExpr, Sort) complement}. The kernel is three-valued: it
 * returns {@link SatResult.Unknown} / {@link ComplementResult.Unknown} when
 * reasoning falls outside the supported fragment, rather than guessing.
 *
 * <p><b>Supported fragment</b> (over {@code Int} and {@code Decimal}): {@code @ op n}
 * for {@code op ∈ {GT, GE, LT, LE, EQ, NE}} and a numeric literal {@code n} (either
 * operand order), {@link SymExpr.And}/{@link SymExpr.Or} of those, and bare
 * {@link SymExpr.Bool} literals. {@code Bool} has its own two-element handling.
 *
 * <p>The ordered-domain reasoning runs on the shared {@link RealIntervalSet}
 * over {@link RealInterval} — the same interval core {@code BoundAnalysis} uses.
 * {@code Int} and {@code Decimal} differ in exactly one place: the {@code Int}
 * result is projected onto the integer grid ({@link RealIntervalSet#quantizeInt})
 * before emptiness is read, so a dense gap like {@code (0,1)} — real-nonempty,
 * integer-empty — is judged empty over {@code Int} and non-empty over
 * {@code Decimal}. Anything outside the fragment returns {@code Unknown}.
 */
public final class PredicateArithmetic {

    private PredicateArithmetic() {}

    /**
     * Is {@code predicate} satisfiable by some value of {@code domain}? A refined
     * domain folds its refinement into the predicate first.
     *
     * @param domain the value domain — a base sort like {@code Sort.of("Int")} or
     *               a refined sort like {@code Sort.refined("Decimal", …)}.
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
            case "Int" -> intervalSatisfiable(effective, true);
            case "Decimal" -> intervalSatisfiable(effective, false);
            case "Bool" -> satisfiableOverBool(effective);
            default -> SatResult.unknown(
                    "supported domains are Int, Decimal and Bool; got base '" + domain.name() + "'");
        };
    }

    /**
     * Computes the predicate covering the values <em>in {@code domain}</em> that
     * do <em>not</em> satisfy {@code predicate} — i.e. {@code domain.predicate ∧
     * ¬predicate}. Used by the match {@code _} default-arm desugar and by match
     * totality (total iff this is unsatisfiable).
     *
     * <p>Returns {@link ComplementResult.Unknown} when {@code predicate} or the
     * domain refinement is outside the kernel's supported fragment.
     */
    public static ComplementResult complement(SymExpr predicate, Sort domain) {
        if (predicate == null) {
            throw new IllegalArgumentException("predicate must be non-null");
        }
        if (domain == null) {
            throw new IllegalArgumentException("domain must be non-null");
        }

        return switch (domain.name()) {
            case "Bool" -> complementOverBool(predicate, domain);
            case "Int" -> intervalComplement(predicate, domain, true);
            case "Decimal" -> intervalComplement(predicate, domain, false);
            default -> ComplementResult.unknown(
                    "supported domains are Int, Decimal and Bool; got base '" + domain.name() + "'");
        };
    }

    // --- Ordered-domain (Int / Decimal) reasoning over RealIntervalSet -------

    private static SatResult intervalSatisfiable(SymExpr predicate, boolean intDomain) {
        RealIntervalSet set = toIntervalSet(predicate);
        if (set == null) {
            return SatResult.unknown(
                    "Predicate outside the ordered-comparison fragment: " + predicate);
        }
        if (intDomain) set = set.quantizeInt();
        return set.isEmpty() ? SatResult.no() : SatResult.yes();
    }

    private static ComplementResult intervalComplement(SymExpr predicate, Sort domain, boolean intDomain) {
        RealIntervalSet predSet = toIntervalSet(predicate);
        if (predSet == null) {
            return ComplementResult.unknown(
                    "Predicate outside the ordered-comparison fragment: " + predicate);
        }
        RealIntervalSet result = predSet.complement();
        if (domain.isRefined()) {
            RealIntervalSet domainSet = toIntervalSet(domain.predicate());
            if (domainSet == null) {
                return ComplementResult.unknown(
                        "Domain refinement outside the ordered-comparison fragment: "
                                + domain.predicate());
            }
            result = result.intersect(domainSet);
        }
        if (intDomain) result = result.quantizeInt();
        return ComplementResult.computed(intervalSetToSymExpr(result));
    }

    /** Interpret {@code expr} as the value set satisfying it ({@code @} the subject); null if outside the fragment. */
    private static RealIntervalSet toIntervalSet(SymExpr expr) {
        if (expr instanceof SymExpr.Bool b) {
            return b.value() ? RealIntervalSet.FULL : RealIntervalSet.EMPTY;
        }
        if (expr instanceof SymExpr.Cmp(SymExpr left, SymExpr.CmpOp op, SymExpr right)) {
            return cmpToIntervalSet(left, op, right);
        }
        if (expr instanceof SymExpr.And(SymExpr l, SymExpr r)) {
            RealIntervalSet lSet = toIntervalSet(l);
            if (lSet == null) return null;
            RealIntervalSet rSet = toIntervalSet(r);
            if (rSet == null) return null;
            return lSet.intersect(rSet);
        }
        if (expr instanceof SymExpr.Or(SymExpr l, SymExpr r)) {
            RealIntervalSet lSet = toIntervalSet(l);
            if (lSet == null) return null;
            RealIntervalSet rSet = toIntervalSet(r);
            if (rSet == null) return null;
            return lSet.union(rSet);
        }
        return null;
    }

    /** A numeric leaf ({@code SymExpr.Lit} integer or {@code SymExpr.Dec}) as an exact BigDecimal. */
    private static BigDecimal asNumericLiteral(SymExpr e) {
        if (e instanceof SymExpr.Lit(long v)) return BigDecimal.valueOf(v);
        if (e instanceof SymExpr.Dec(BigDecimal v)) return v;
        return null;
    }

    /** {@code @ op c} / {@code c op @} — strict ops give OPEN bounds (kept exact for Decimal). */
    private static RealIntervalSet cmpToIntervalSet(SymExpr left, SymExpr.CmpOp op, SymExpr right) {
        BigDecimal n;
        SymExpr.CmpOp effectiveOp;
        if (left instanceof SymExpr.Self && (n = asNumericLiteral(right)) != null) {
            effectiveOp = op;
        } else if (right instanceof SymExpr.Self && (n = asNumericLiteral(left)) != null) {
            effectiveOp = flip(op);
        } else {
            return null;
        }
        return switch (effectiveOp) {
            case GT -> RealIntervalSet.of(RealInterval.atLeast(n, false));
            case GE -> RealIntervalSet.of(RealInterval.atLeast(n, true));
            case LT -> RealIntervalSet.of(RealInterval.atMost(n, false));
            case LE -> RealIntervalSet.of(RealInterval.atMost(n, true));
            case EQ -> RealIntervalSet.of(RealInterval.point(n));
            case NE -> RealIntervalSet.ofAll(java.util.List.of(
                    RealInterval.atMost(n, false), RealInterval.atLeast(n, false)));
        };
    }

    /** Inverse of {@link #toIntervalSet} for the canonical interval-set fragment. */
    private static SymExpr intervalSetToSymExpr(RealIntervalSet set) {
        if (set.isEmpty()) return SymExpr.bool(false);
        java.util.List<RealInterval> intervals = set.intervals();
        SymExpr result = intervalToSymExpr(intervals.get(0));
        for (int i = 1; i < intervals.size(); i++) {
            result = SymExpr.or(result, intervalToSymExpr(intervals.get(i)));
        }
        return result;
    }

    private static SymExpr intervalToSymExpr(RealInterval iv) {
        if (iv.lo() == null && iv.hi() == null) return SymExpr.bool(true);
        if (iv.lo() != null && iv.hi() != null && iv.lo().compareTo(iv.hi()) == 0) {
            return SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.EQ, numToSymExpr(iv.lo()));
        }
        SymExpr lower = iv.lo() == null ? null : SymExpr.cmp(
                SymExpr.self(), iv.loIncl() ? SymExpr.CmpOp.GE : SymExpr.CmpOp.GT, numToSymExpr(iv.lo()));
        SymExpr upper = iv.hi() == null ? null : SymExpr.cmp(
                SymExpr.self(), iv.hiIncl() ? SymExpr.CmpOp.LE : SymExpr.CmpOp.LT, numToSymExpr(iv.hi()));
        if (lower == null) return upper;
        if (upper == null) return lower;
        return SymExpr.and(lower, upper);
    }

    /** Render an integer-valued endpoint as an {@code Int} literal, otherwise a {@code Decimal} one. */
    private static SymExpr numToSymExpr(BigDecimal v) {
        BigDecimal stripped = v.stripTrailingZeros();
        if (stripped.scale() <= 0) {
            try {
                return SymExpr.lit(stripped.longValueExact());
            } catch (ArithmeticException overflow) {
                // Falls through to the exact decimal form below.
            }
        }
        return SymExpr.dec(v);
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

    // --- Internal: Bool-domain satisfiability via the two-element value set ---

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
     * counterpart of {@link RealIntervalSet} for the two-element domain.
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
}
