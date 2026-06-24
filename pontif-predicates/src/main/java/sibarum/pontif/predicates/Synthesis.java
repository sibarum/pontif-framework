package sibarum.pontif.predicates;

import sibarum.pontif.core.symbolic.ArithmeticRules;
import sibarum.pontif.core.symbolic.BooleanRules;
import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.RefinementRules;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.Substitute;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.types.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Value synthesis from a refinement — done BY the prover, reusing the membership
 * engine that guards parameters.
 *
 * <p>The unifying observation (war/unify-synthesis-prover): a refinement
 * {@code [Int:P]} denotes a <em>set</em>. Checking a scalar against it is
 * <em>membership</em> ({@code x:[Int:@>=1]}); synthesizing a {@code Stream} from it
 * is the <em>enumeration</em> of that same set ({@code Stream[Int:0<=@<10 & @!=5]}).
 * One predicate, two consumers. This routine enumerates the integer extension of
 * {@code P} by:
 *
 * <ol>
 *   <li>deriving the candidate domain {@code [lo,hi]} with {@link BoundAnalysis} —
 *       the <em>only</em> special role the order-bounds play is making the domain
 *       finite and iterable; and
 *   <li>keeping each candidate {@code n} where {@link Refinements#satisfies} proves
 *       {@code n ∈ [Int:P]} — the <em>exact</em> membership engine a parameter guard
 *       {@code x:[Int:P]} runs. There is no second predicate evaluator.
 * </ol>
 *
 * <p>A pinned witness ({@code let x:[Int:@>-1 & @<1]} → {@code 0}) is the size-1 case;
 * a finite stream is the size-N case — the same routine, packaged by whether the
 * declared type is a {@code Stream}.
 *
 * <p>Traversal direction is read from the predicate: descending iff the first
 * {@code @}-vs-literal order bound (left-to-right) is an <em>upper</em> bound
 * ({@code @<10 & @>2} → 9,8,…; {@code @>2 & @<10} → 3,4,…). Membership is
 * order-agnostic; only the emission order is read here.
 */
public final class Synthesis {

    private Synthesis() {}

    /** The bound engine reads an ordinary named subject, not the refinement's {@code @}. */
    private static final String SUBJECT = "@synth";

    /**
     * Folds a candidate's <em>ground</em> membership predicate (every {@code @}
     * replaced by a concrete literal) to a boolean. Only the arithmetic /
     * comparison / boolean fragments are needed — no hypothesis or bound reasoning,
     * since nothing symbolic survives the substitution. {@link RefinementRules}
     * carries the {@code Cmp(Lit,Lit)} fold, {@link ArithmeticRules} the literal
     * arithmetic, {@link BooleanRules} the {@code And/Or} folds. The verdict matches
     * the production guard check for a ground predicate (bound rules can't change a
     * fully-concrete fold), so a value synthesized as "in the set" also passes the
     * guard elsewhere — no-lie consistency.
     */
    private static final Simplifier MEMBERSHIP = membershipSimplifier();

    private static Simplifier membershipSimplifier() {
        List<RewriteRule> rules = new ArrayList<>();
        rules.addAll(RefinementRules.all());
        rules.addAll(ArithmeticRules.all());
        rules.addAll(BooleanRules.all());
        return new Simplifier(rules);
    }

    /**
     * The ordered, membership-filtered integer extension of {@code refinement}, or
     * empty when it is not a finitely-enumerable integer refinement (a non-Int base,
     * a side that is unbounded, or a candidate the membership engine leaves undecided
     * — all honestly "not synthesizable", never a guess). A both-sided-bounded
     * predicate with no integer solutions ({@code 5<@<6}) yields an <em>empty</em>
     * extension (a valid empty stream), distinct from "unbounded".
     */
    public static Optional<List<Long>> enumerateIntExtension(Sort refinement) {
        if (refinement == null || !refinement.isRefined() || !"Int".equals(refinement.name())) {
            return Optional.empty();
        }
        SymExpr predicate = refinement.predicate();
        // Domain: canonicalize each comparison so `@` is on the LEFT (the bound
        // engine reads `@ op k` as a bound on @ but not the flipped `k op @`), then
        // rephrase `@` as a named subject the engine treats as an ordinary variable.
        SymExpr canonical = selfLeft(predicate);
        SymExpr overSubject = Substitute.applySelf(canonical, SymExpr.var(SUBJECT));
        Interval domain = BoundAnalysis.bound(SymExpr.var(SUBJECT), List.of(overSubject));
        if (domain.lo() == Interval.NEG_INF || domain.hi() == Interval.POS_INF) {
            return Optional.empty();                       // unbounded on a side — not enumerable
        }
        boolean descending = firstOrderBoundIsUpper(predicate);
        long from = descending ? domain.hi() : domain.lo();
        long to = descending ? domain.lo() : domain.hi();
        long step = descending ? -1 : 1;
        List<Long> extension = new ArrayList<>();
        for (long v = from; descending ? v >= to : v <= to; v += step) {
            ProofResult member = Refinements.satisfies(SymExpr.lit(v), refinement, MEMBERSHIP);
            if (member instanceof ProofResult.Passed) {
                extension.add(v);                          // in the set: emit it
            } else if (member instanceof ProofResult.Residual) {
                // The membership engine couldn't decide this candidate — refuse to
                // guess (no-lie). The whole synthesis is not reliable.
                return Optional.empty();
            }
            // Failed → the candidate is out of the set: drop it (the filter disposition).
        }
        return Optional.of(extension);
    }

    /**
     * True (descending) iff the first {@code @}-vs-literal order comparison in
     * left-to-right order is an upper bound.
     */
    private static boolean firstOrderBoundIsUpper(SymExpr predicate) {
        return Boolean.TRUE.equals(scanFirstBound(predicate));
    }

    private static Boolean scanFirstBound(SymExpr e) {
        if (e instanceof SymExpr.And(SymExpr l, SymExpr r)) {
            Boolean fromLeft = scanFirstBound(l);
            return fromLeft != null ? fromLeft : scanFirstBound(r);
        }
        if (e instanceof SymExpr.Cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r)) {
            boolean leftSelf = l instanceof SymExpr.Self;
            boolean rightSelf = r instanceof SymExpr.Self;
            if (leftSelf == rightSelf) return null;            // need exactly one `@`
            SymExpr other = leftSelf ? r : l;
            if (!(other instanceof SymExpr.Lit)) return null;  // a static order bound only
            SymExpr.CmpOp normalized = leftSelf ? op : flip(op);   // normalize to `@ <op> k`
            return switch (normalized) {
                case LT, LE -> Boolean.TRUE;                   // upper bound → descending
                case GT, GE -> Boolean.FALSE;                  // lower bound → ascending
                case EQ, NE -> null;                           // not a direction bound
            };
        }
        return null;
    }

    /**
     * Canonicalizes every comparison so {@code @} sits on the left ({@code k op @}
     * → {@code @ flip(op) k}), recursing through {@code And}/{@code Or}. The bound
     * engine reads {@code @ op literal} as a bound on {@code @} but not the flipped
     * orientation, so a lower bound written {@code 0 <= @} would otherwise be lost.
     */
    private static SymExpr selfLeft(SymExpr e) {
        if (e instanceof SymExpr.And(SymExpr l, SymExpr r)) {
            return new SymExpr.And(selfLeft(l), selfLeft(r));
        }
        if (e instanceof SymExpr.Or(SymExpr l, SymExpr r)) {
            return new SymExpr.Or(selfLeft(l), selfLeft(r));
        }
        if (e instanceof SymExpr.Cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r)
                && r instanceof SymExpr.Self && !(l instanceof SymExpr.Self)) {
            return new SymExpr.Cmp(r, flip(op), l);
        }
        return e;
    }

    /** {@code k <op> @} ⟺ {@code @ <flip> k}. */
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
}
