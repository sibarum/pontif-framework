package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.Substitute;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The facts that hold along a single branch's path through a
 * {@link Node}, plus the result var's body definition. Shared by the
 * {@link BuiltinIssuer} (which discharges an obligation) and the
 * {@link Notary} (which tries to refute its negation) — same gathered
 * facts, opposite goals.
 *
 * <p>Gathered facts:
 * <ul>
 *   <li>each refined parameter's predicate, bound to the param var
 *       (e.g. {@code n_0 > 0} from {@code n:[Int:@>0]}) — a function may
 *       assume its parameter refinements throughout its body;</li>
 *   <li>the branch guard (if any);</li>
 *   <li>each sub-call's inductive hypothesis — the call result var's
 *       refinement bound to that var (e.g. {@code r_1 >= 1}), which is
 *       how the no-duplicate-edges back-reference carries a
 *       postcondition forward;</li>
 *   <li>any non-defining body receipts.</li>
 * </ul>
 *
 * <p>The result var's defining receipt ({@code r_0 == rhs}) is held
 * separately so callers can substitute {@code rhs} into a goal
 * expressed over the result var.
 */
final class PathFacts {

    private final String resultVarName;
    private final SymExpr definitionRhs;  // nullable — no `r == rhs` receipt
    private final List<SymExpr> hypotheses;

    private PathFacts(String resultVarName, SymExpr definitionRhs, List<SymExpr> hypotheses) {
        this.resultVarName = resultVarName;
        this.definitionRhs = definitionRhs;
        this.hypotheses = List.copyOf(hypotheses);
    }

    /** Gathers the path facts for {@code branch} under {@code node}'s result var. */
    static PathFacts of(Node node, Branch branch) {
        String resultVarName = node.resultVar().name();
        SymExpr definitionRhs = null;
        List<SymExpr> hyps = new ArrayList<>();

        // Parameter refinements hold throughout the body — a function may
        // assume them (the dispatcher guarantees args satisfy them). So a
        // param n_0:[Int:@>0] contributes the hypothesis n_0 > 0 on every
        // branch. Without this, functions that constrain via parameter sorts
        // (rather than match-arm guards) can't discharge anything that
        // depends on the parameter's sign/bound.
        for (Param p : node.params()) {
            if (p.sort().isRefined()) {
                hyps.add(Substitute.applySelf(p.sort().predicate(), SymExpr.var(p.name())));
            }
        }

        branch.guard().ifPresent(hyps::add);

        for (CallRef call : branch.calls()) {
            Sort callResultSort = call.resultVar().sort();
            if (callResultSort.isRefined()) {
                hyps.add(Substitute.applySelf(
                        callResultSort.predicate(), SymExpr.var(call.resultVar().name())));
            }
        }

        for (InitialReceipt r : branch.initialReceipts()) {
            if (isDefinitionOf(r.claim(), resultVarName)) {
                definitionRhs = ((SymExpr.Cmp) r.claim()).right();
            } else {
                hyps.add(r.claim());
            }
        }

        return new PathFacts(resultVarName, definitionRhs, hyps);
    }

    /** The facts that hold on this path (guard, IHs, non-defining receipts). */
    List<SymExpr> hypotheses() {
        return hypotheses;
    }

    /**
     * Substitutes the result var's body definition into {@code goal}
     * (a claim expressed over the result var), so a goal like
     * {@code r_0 >= 0} becomes {@code rhs >= 0}. Returns {@code goal}
     * unchanged when there's no defining receipt.
     */
    SymExpr substituteDefinition(SymExpr goal) {
        return definitionRhs != null
                ? Substitute.apply(goal, Map.of(resultVarName, definitionRhs))
                : goal;
    }

    private static boolean isDefinitionOf(SymExpr claim, String resultVarName) {
        return claim instanceof SymExpr.Cmp(SymExpr l, SymExpr.CmpOp op, SymExpr ignored)
                && op == SymExpr.CmpOp.EQ
                && l instanceof SymExpr.Var v
                && v.name().equals(resultVarName);
    }
}
