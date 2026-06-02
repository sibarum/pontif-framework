package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.SymExpr;

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
                yield new Outcome.LeafOutcome(splitGuards, Discharge.discharge(domain, hyps, goal));
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
