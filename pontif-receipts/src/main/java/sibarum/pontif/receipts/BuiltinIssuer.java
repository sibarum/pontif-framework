package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.Substitute;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pontif's built-in default issuer. Walks a {@link ReceiptGraph} and
 * emits {@link ClosingReceipt}s for the obligations it can discharge via
 * {@link IntegerDischarge} — a thin wrapper over the {@code BoundAnalysis}
 * linear-bound + sign engine, the trivial fragment over the integer domain.
 *
 * <p>Trusted by the notary by default. For obligations beyond this
 * fragment, no receipt is produced and the runtime check remains the
 * safety net (or an oracle module would step in).
 *
 * <h2>Per-branch discharge</h2>
 * For each node with a refined result var, the obligation is the
 * result refinement with {@code @} bound to the result var (e.g.
 * {@code r_0 >= 0}). For each branch:
 * <ol>
 *   <li>The result var's <em>body definition</em> ({@code r_0 == expr})
 *       is substituted into the obligation, turning {@code r_0 >= 0}
 *       into {@code expr >= 0} (e.g. {@code x_0 * x_0 >= 0}).</li>
 *   <li>Hypotheses are gathered ({@link PathFacts}): the branch guard,
 *       each sub-call's inductive hypothesis (the call result var's
 *       refinement, e.g. {@code r_1 >= 1}), and any non-defining body
 *       receipts.</li>
 *   <li>{@link IntegerDischarge#discharge} attempts the proof. On success
 *       a closing receipt is emitted referencing this branch.</li>
 * </ol>
 *
 * <p>This is <b>eager-close</b> mode — derive whatever the issuer can
 * with no specific target. Hypothesis-driven close (a specific goal)
 * is a later addition.
 */
public final class BuiltinIssuer {

    /** Issuer identifier carried by every receipt this issuer produces. */
    public static final String ISSUER_ID = "<pontif-default>";

    /**
     * Attribution for an obligation discharged not by the built-in engine but
     * by a human-supplied {@link Refinement} proof the kernel validated
     * ({@link RefinementValidator}).
     */
    public static final String REFINEMENT_ISSUER_ID = "<refinement-proof>";

    private BuiltinIssuer() {}

    /**
     * One per-branch obligation the issuer considered, with its outcome and
     * the reasoning context the issuer worked with: the path facts in scope
     * (param refinements, branch guard, back-reference IHs, non-defining
     * receipts) and the substituted goal (the obligation after the result
     * var's body definition is inlined — what the engine actually tried to
     * prove). {@code discharged} is whether {@link IntegerDischarge} proved
     * the goal under those hypotheses. Surfacing failures alongside the
     * goal/hypotheses is what lets a report say "tried {@code r_1 + 5 >= 10}
     * with {@code r_1 >= 5}, discharged" — readers see *why* the proof works
     * (or doesn't). {@code provenByRefinement} is true when the built-in
     * engine couldn't discharge the goal but a supplied {@link Refinement}
     * proof did (validated by {@link RefinementValidator}).
     */
    public record Attempt(
            int nodeIndex,
            int branchIndex,
            SymExpr obligation,
            List<SymExpr> hypotheses,
            SymExpr substitutedGoal,
            boolean discharged,
            boolean provenByRefinement) {
        public Attempt {
            hypotheses = List.copyOf(hypotheses);
        }
    }

    /**
     * Every per-branch obligation attempt across the graph, discharged or
     * not. The obligation is the result var's refinement with {@code @}
     * bound to the result var. Order: node, then branch.
     */
    public static List<Attempt> attemptAll(ReceiptGraph graph) {
        return attemptAll(graph, Map.of());
    }

    /**
     * As {@link #attemptAll(ReceiptGraph)}, but a branch the built-in engine
     * can't discharge gets a second chance: if {@code proofs} supplies a
     * {@link Refinement} for that branch's {@link GraphReference}, it's
     * validated ({@link RefinementValidator}) and, if every leaf discharges,
     * the obligation counts as discharged <em>by proof</em>
     * ({@link Attempt#provenByRefinement}).
     *
     * <p>Soundness is the validator's, and it's the whole point: a
     * conservative split can rescue a true-but-hard obligation (e.g.
     * {@code isSparse}) but can never validate a false one — no split makes a
     * false leaf discharge — so a bogus proof simply fails to validate and the
     * branch stays open. The proof-supply path extends reach without extending
     * trust.
     */
    public static List<Attempt> attemptAll(
            ReceiptGraph graph, Map<GraphReference, Refinement> proofs) {
        List<Attempt> attempts = new ArrayList<>();
        List<Node> nodes = graph.roots();
        for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
            Node node = nodes.get(nodeIndex);
            Sort resultSort = node.resultVar().sort();
            if (!resultSort.isRefined()) {
                continue;  // no obligation to discharge
            }
            SymExpr obligation = Substitute.applySelf(
                    resultSort.predicate(), SymExpr.var(node.resultVar().name()));
            for (int branchIndex = 0; branchIndex < node.branches().size(); branchIndex++) {
                Branch branch = node.branches().get(branchIndex);
                PathFacts facts = PathFacts.of(node, branch);
                SymExpr goal = facts.substituteDefinition(obligation);
                boolean engineOk = IntegerDischarge.discharge(facts.hypotheses(), goal);
                boolean viaProof = false;
                if (!engineOk) {
                    Refinement proof = proofs.get(new GraphReference(nodeIndex, branchIndex));
                    viaProof = proof != null
                            && RefinementValidator.validate(node, branch, obligation, proof).verified();
                }
                attempts.add(new Attempt(
                        nodeIndex, branchIndex, obligation,
                        facts.hypotheses(), goal, engineOk || viaProof, viaProof));
            }
        }
        return attempts;
    }

    /**
     * Eager-close: the discharged subset of {@link #attemptAll}, as
     * closing receipts. Each receipt's conclusion is the discharged
     * obligation; its reference points at the branch that discharged it.
     */
    public static List<ClosingReceipt> close(ReceiptGraph graph) {
        return close(graph, Map.of());
    }

    /**
     * As {@link #close(ReceiptGraph)}, but consulting supplied
     * {@link Refinement} proofs. A receipt closed by a validated proof is
     * attributed to {@link #REFINEMENT_ISSUER_ID} rather than the built-in
     * engine.
     */
    public static List<ClosingReceipt> close(
            ReceiptGraph graph, Map<GraphReference, Refinement> proofs) {
        List<ClosingReceipt> receipts = new ArrayList<>();
        for (Attempt a : attemptAll(graph, proofs)) {
            if (a.discharged()) {
                receipts.add(new ClosingReceipt(
                        a.provenByRefinement() ? REFINEMENT_ISSUER_ID : ISSUER_ID,
                        a.obligation(),
                        new GraphReference(a.nodeIndex(), a.branchIndex()),
                        Map.of()));
            }
        }
        return receipts;
    }

}
