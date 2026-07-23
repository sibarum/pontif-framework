package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.predicates.Interval;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates that a {@link Refinement} of a branch discharges an obligation.
 *
 * <p><b>Coverage and disjointness are not checked here</b> — they are
 * structural invariants of {@link Refinement.Split} (a predicate paired with
 * its exact complement; see {@link Refinement}). This validator verifies only
 * <b>per-leaf discharge</b>: it walks the tree, accumulating each split's
 * guard down every path, and at each leaf asks {@link IntegerDischarge}
 * whether the obligation — with the branch's body definition substituted in —
 * follows from the path facts ({@link PathFacts}) plus the accumulated split
 * guards. The branch's obligation is proven iff every leaf discharges.
 *
 * <p>The result mirrors the refinement tree, so the entire case analysis is
 * inspectable: the guard each leaf sits under, and whether it closed. This is
 * the same trust-equals-traceability property the rest of the receipt graph
 * has — the gating of each step is what records it.
 */
final class RefinementValidator {

    private RefinementValidator() {}

    /**
     * Validate {@code refinement} of {@code branch} (under {@code node})
     * against {@code obligation} (a claim over the result var, e.g.
     * {@code r_0 >= -16}).
     */
    static Result validate(Node node, Branch branch, SymExpr obligation, Refinement refinement) {
        PathFacts facts = PathFacts.of(node, branch);
        SymExpr goal = facts.substituteDefinition(obligation);
        // Per-leaf discharge routes by the obligation's domain (Int vs Decimal).
        sibarum.pontif.core.types.Sort domain = node.resultVar().sort();
        Outcome tree = walk(refinement, facts.hypotheses(), List.of(), goal, domain);
        return new Result(obligation, goal, tree);
    }

    private static Outcome walk(
            Refinement refinement,
            List<SymExpr> baseFacts,
            List<SymExpr> splitGuards,
            SymExpr goal,
            sibarum.pontif.core.types.Sort domain) {
        return switch (refinement) {
            case Refinement.Leaf ignored -> {
                List<SymExpr> hyps = new ArrayList<>(baseFacts);
                hyps.addAll(splitGuards);
                if (Discharge.discharge(domain, hyps, goal)) {
                    yield new Outcome.LeafOutcome(splitGuards, true);
                }
                // Auto-peel: if the accumulated guards pin a single variable to a
                // finite integer interval, that residual IS a bounded region — so
                // enumerate it to singletons (the work the Singletons directive once
                // did explicitly) and re-walk. Each peeled cell re-attempts discharge
                // with the variable fixed to one value, where interval arithmetic is
                // exact. Declines (honest non-discharge) when no single variable is
                // finitely bounded, the residual is already a point, or it is too
                // wide to enumerate.
                Outcome peeled = autoPeel(baseFacts, splitGuards, goal, domain);
                yield peeled != null ? peeled : new Outcome.LeafOutcome(splitGuards, false);
            }
            case Refinement.Split(SymExpr p, Refinement whenTrue, Refinement whenFalse) -> {
                Outcome t = walk(whenTrue, baseFacts, append(splitGuards, p), goal, domain);
                Outcome f = walk(whenFalse, baseFacts, append(splitGuards, Refinement.complement(p)), goal, domain);
                yield new Outcome.SplitOutcome(p, t, f);
            }
        };
    }

    private static List<SymExpr> append(List<SymExpr> guards, SymExpr g) {
        List<SymExpr> next = new ArrayList<>(guards);
        next.add(g);
        return next;
    }

    /** Largest residual interval auto-peel will enumerate; wider residuals decline. */
    private static final long MAX_PEEL_SIZE = 1024;

    /**
     * When a leaf fails to discharge but the accumulated guards pin a single
     * variable to a finite integer interval, enumerate that interval to
     * singletons via {@link Refinement#splitToSingletons} and re-walk. The
     * residual left by the enclosing splits IS the interval — so the bounds the
     * {@code Singletons} directive used to carry explicitly are derived here from
     * the guards. Returns the peeled (discharged) sub-outcome, or {@code null}
     * to decline: no single cut variable, an infinite or single-point residual,
     * an interval wider than {@link #MAX_PEEL_SIZE}, or a peel that still doesn't
     * discharge. Declining is sound — auto-peel can only widen reach, never
     * launder a false leaf.
     */
    private static Outcome autoPeel(
            List<SymExpr> baseFacts,
            List<SymExpr> splitGuards,
            SymExpr goal,
            sibarum.pontif.core.types.Sort domain) {
        SymExpr subject = soleCutVariable(splitGuards);
        if (subject == null) {
            return null;
        }
        List<SymExpr> hyps = new ArrayList<>(baseFacts);
        hyps.addAll(splitGuards);
        Interval iv = Interval.of(subject, hyps);
        if (iv.lo() == Interval.NEG_INF || iv.hi() == Interval.POS_INF) {
            return null;  // not a finite interval — nothing to enumerate
        }
        long width = iv.hi() - iv.lo();
        // width <= 0: a single point (peeling can't help) or an overflowed
        // difference — also stops the recursion a failed singleton would drive.
        // width >= cap: decline to auto-enumerate something this large (the
        // residual holds width + 1 integers).
        if (width <= 0 || width >= MAX_PEEL_SIZE) {
            return null;
        }
        Refinement ladder = Refinement.splitToSingletons(subject, iv.lo(), iv.hi());
        Outcome peeled = walk(ladder, baseFacts, splitGuards, goal, domain);
        return peeled.discharged() ? peeled : null;
    }

    /**
     * The single variable the guards cut on (the left of every comparison guard),
     * or {@code null} when the guards cut on zero or more than one variable —
     * auto-peel only enumerates a single-variable residual. A guard with a
     * non-variable left (e.g. literal-on-left) is skipped, so such a proof simply
     * doesn't auto-peel rather than misidentifying its subject.
     */
    private static SymExpr soleCutVariable(List<SymExpr> guards) {
        SymExpr subject = null;
        for (SymExpr g : guards) {
            if (!(g instanceof SymExpr.Cmp cmp)) {
                continue;
            }
            SymExpr left = cmp.left();
            if (!(left instanceof SymExpr.Var) && !(left instanceof SymExpr.Self)) {
                continue;
            }
            if (subject == null) {
                subject = left;
            } else if (!subject.equals(left)) {
                return null;  // more than one cut variable → out of scope
            }
        }
        return subject;
    }

    /** The outcome of validating a refinement against one branch's obligation. */
    record Result(SymExpr obligation, SymExpr substitutedGoal, Outcome tree) {
        /** True iff every leaf of the refinement discharged. */
        boolean verified() {
            return tree.discharged();
        }
    }

    /** A node of the per-leaf outcome tree, mirroring the {@link Refinement}. */
    sealed interface Outcome permits Outcome.LeafOutcome, Outcome.SplitOutcome {

        /** Whether this subtree fully discharged (all its leaves closed). */
        boolean discharged();

        /** A leaf, with the split guards it sat under and whether it closed. */
        record LeafOutcome(List<SymExpr> guards, boolean discharged) implements Outcome {
            public LeafOutcome {
                guards = List.copyOf(guards);
            }
        }

        /** A split; discharges iff both sides discharge. */
        record SplitOutcome(SymExpr predicate, Outcome whenTrue, Outcome whenFalse)
                implements Outcome {
            @Override
            public boolean discharged() {
                return whenTrue.discharged() && whenFalse.discharged();
            }
        }
    }
}
