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
 * {@link IntegerDischarge} (sign analysis + simple equational closings +
 * the integer-strictness bridge) — the trivial fragment.
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

    private BuiltinIssuer() {}

    /**
     * Eager-close: returns every closing receipt the issuer can derive
     * from {@code graph}. Each receipt's conclusion is the discharged
     * obligation; its reference points at the branch that discharged it.
     */
    public static List<ClosingReceipt> close(ReceiptGraph graph) {
        List<ClosingReceipt> receipts = new ArrayList<>();
        for (Node node : graph.roots().values()) {
            Sort resultSort = node.resultVar().sort();
            if (!resultSort.isRefined()) {
                continue;  // no obligation to discharge
            }
            SymExpr obligation = Substitute.applySelf(
                    resultSort.predicate(), SymExpr.var(node.resultVar().name()));

            for (int i = 0; i < node.branches().size(); i++) {
                Branch branch = node.branches().get(i);
                if (dischargeable(node, branch, obligation)) {
                    receipts.add(new ClosingReceipt(
                            ISSUER_ID,
                            obligation,
                            new GraphReference(node.functionName(), i),
                            Map.of()));
                }
            }
        }
        return receipts;
    }

    // --- Discharge logic ---------------------------------------------------

    private static boolean dischargeable(Node node, Branch branch, SymExpr obligation) {
        PathFacts facts = PathFacts.of(node, branch);
        SymExpr goal = facts.substituteDefinition(obligation);
        return IntegerDischarge.discharge(facts.hypotheses(), goal);
    }
}
