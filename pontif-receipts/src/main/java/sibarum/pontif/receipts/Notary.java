package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.IrModule;

/**
 * Pontif's built-in receipt-graph verifier. <b>Existence and consent,
 * not correctness.</b> Three independently-invocable verifications:
 *
 * <ol>
 *   <li>{@link #graphExists} — a receipt-graph is present for the code
 *       under examination.</li>
 *   <li>{@link #skeletonMatches} — re-drafts a fresh graph from the
 *       same source and confirms it agrees with the provided graph.
 *       Because the {@link Drafter} is deterministic, agreement is full
 *       structural equality; disagreement means a tampered or stale
 *       graph.</li>
 *   <li>{@link #hypothesisSupported} — given a graph and a closing
 *       receipt, locates the referenced path and checks whether
 *       anything on it <em>refutes</em> the receipt's conclusion. The
 *       notary never confirms validity; it only fails to refute. A
 *       conclusion the notary can't refute is <b>accepted</b> — which
 *       means "not refuted yet," not "proven."</li>
 * </ol>
 *
 * <p>Like the drafter, the notary is not pluggable — it changes only
 * across Pontif language versions.
 */
public final class Notary {

    private Notary() {}

    /** Verification 1: a graph is present (non-null with at least one root). */
    public static boolean graphExists(ReceiptGraph graph) {
        return graph != null && !graph.roots().isEmpty();
    }

    /**
     * Verification 2: {@code graph} agrees with a fresh draft from
     * {@code source}. Guards against tampered or stale graphs.
     */
    public static boolean skeletonMatches(ReceiptGraph graph, IrModule source)
            throws CompileException {
        ReceiptGraph fresh = Drafter.draft(source);
        return fresh.equals(graph);
    }

    /**
     * Verification 3: is {@code receipt}'s conclusion supported (not
     * refuted) by {@code graph}? Locates the referenced node + branch,
     * gathers the path's facts, and checks whether they entail the
     * <em>negation</em> of the conclusion. If the negation is
     * dischargeable, the conclusion is refuted → <b>rejected</b>.
     * Otherwise → <b>accepted</b> (not refuted).
     *
     * @return a {@link Verdict} — accepted or rejected, with a reason
     */
    public static Verdict hypothesisSupported(ReceiptGraph graph, ClosingReceipt receipt) {
        GraphReference ref = receipt.reference();
        Node node = graph.roots().get(ref.functionName());
        if (node == null) {
            return Verdict.rejected(
                    "no node '" + ref.functionName() + "' in graph for the receipt's reference");
        }
        if (ref.branchIndex() < 0 || ref.branchIndex() >= node.branches().size()) {
            return Verdict.rejected(
                    "branch index " + ref.branchIndex() + " out of range for node '"
                            + ref.functionName() + "' (" + node.branches().size() + " branches)");
        }
        Branch branch = node.branches().get(ref.branchIndex());

        SymExpr negation = negate(receipt.conclusion());
        if (negation == null) {
            // Can't negate a non-comparison conclusion — nothing to refute
            // with the current kernel, so we can't reject. Accept (not refuted).
            return Verdict.accepted("conclusion is not a comparison; nothing refutes it");
        }

        PathFacts facts = PathFacts.of(node, branch);
        SymExpr negatedGoal = facts.substituteDefinition(negation);
        boolean refuted = IntegerDischarge.discharge(facts.hypotheses(), negatedGoal);

        return refuted
                ? Verdict.rejected("path facts entail " + negatedGoal + ", refuting the conclusion")
                : Verdict.accepted("nothing on the path refutes the conclusion");
    }

    /** Negates a comparison; returns null for non-comparisons. */
    private static SymExpr negate(SymExpr conclusion) {
        if (!(conclusion instanceof SymExpr.Cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r))) {
            return null;
        }
        SymExpr.CmpOp negated = switch (op) {
            case GE -> SymExpr.CmpOp.LT;
            case GT -> SymExpr.CmpOp.LE;
            case LE -> SymExpr.CmpOp.GT;
            case LT -> SymExpr.CmpOp.GE;
            case EQ -> SymExpr.CmpOp.NE;
            case NE -> SymExpr.CmpOp.EQ;
        };
        return new SymExpr.Cmp(l, negated, r);
    }

    /**
     * Outcome of a hypothesis-support check. {@code accepted} does not
     * mean validated — it means the notary failed to refute.
     */
    public record Verdict(boolean accepted, String reason) {
        public static Verdict accepted(String reason) { return new Verdict(true, reason); }
        public static Verdict rejected(String reason) { return new Verdict(false, reason); }
    }
}
