package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.Substitute;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrParam;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;
import sibarum.pontif.predicates.BoundAnalysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Binds {@code assign proof} return-refinement proofs ({@link IrStmt.ReturnProof})
 * to the body branches they prove — "proof dispatch". Unlike the shared
 * {@code proof = <tree>} binder ({@link ProofBinding}), the granted refinement
 * lives on the proof: a function declares a <b>base</b> return and one proof
 * <b>per region</b> grants the refinement for that region.
 *
 * <p>Each proof's parameter refinement (e.g. {@code d:[Int:@<0]}) is matched to
 * the body branch whose first-match effective region it equals — the region of
 * branch <i>k</i> is {@code guard_k ∧ ¬guard_0 ∧ … ∧ ¬guard_{k-1}}. The proof's
 * case-function body is lowered to a {@link Refinement} and validated against an
 * obligation built from the granted return. A single whole-domain proof on a
 * single-branch function is the degenerate case (empty region matches the one
 * unguarded branch), so this handles both the single-proof and dispatched forms.
 *
 * <p>Soundness stays with {@link RefinementValidator}: a mis-matched or too-weak
 * proof simply fails to discharge its branch. The region match is for intent and
 * diagnostics, and for routing each proof to the right branch's hypotheses.
 */
public final class ReturnProofBinding {

    private ReturnProofBinding() {}

    /**
     * Validates every {@code assign proof} against its matched branch. Returns
     * the first problem (unknown/overloaded target, a region matching no branch,
     * two proofs on one branch, a proof that doesn't discharge), or empty when
     * all proofs bind and discharge. v1: single-overload targets.
     */
    public static Optional<String> validate(List<IrStmt.ReturnProof> proofs, ReceiptGraph graph) {
        Map<String, List<Integer>> nodeIdxByName = new HashMap<>();
        for (int i = 0; i < graph.roots().size(); i++) {
            nodeIdxByName.computeIfAbsent(graph.roots().get(i).functionName(),
                    k -> new ArrayList<>()).add(i);
        }
        Map<String, List<IrStmt.ReturnProof>> byFn = new LinkedHashMap<>();
        for (IrStmt.ReturnProof rp : proofs) {
            byFn.computeIfAbsent(rp.functionName(), k -> new ArrayList<>()).add(rp);
        }
        for (Map.Entry<String, List<IrStmt.ReturnProof>> e : byFn.entrySet()) {
            String fn = e.getKey();
            List<Integer> idxs = nodeIdxByName.get(fn);
            if (idxs == null || idxs.isEmpty()) {
                return Optional.of("assign proof references unknown function '" + fn + "'.");
            }
            if (idxs.size() > 1) {
                return Optional.of("assign proof for '" + fn
                        + "' targets an overloaded function — not supported yet.");
            }
            Node node = graph.roots().get(idxs.get(0));
            Set<Integer> covered = new HashSet<>();
            for (IrStmt.ReturnProof rp : e.getValue()) {
                Optional<String> problem = bindOne(fn, node, rp, covered);
                if (problem.isPresent()) {
                    return problem;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> bindOne(
            String fn, Node node, IrStmt.ReturnProof rp, Set<Integer> covered) {
        Map<String, SymExpr> rename = new HashMap<>();
        for (IrParam p : rp.params()) {
            rename.put(p.name(), SymExpr.var(p.name() + "_0"));
        }
        List<SymExpr> region;
        SymExpr obligation;
        Refinement refinement;
        try {
            region = regionConjuncts(rp.params());
            obligation = obligationOf(rp.grantedReturn(), node.resultVar().name(), rename);
            refinement = rp.body() == null
                    ? Refinement.leaf()
                    : RefinementProof.fromCaseFunction(asMatch(rp.body()), rename);
        } catch (CompileException ce) {
            return Optional.of("assign proof for '" + fn + "' could not be used: " + ce.getMessage());
        }
        if (obligation == null) {
            return Optional.empty();  // granted a base sort — nothing to prove
        }
        int branchIdx = matchBranch(node, region);
        if (branchIdx < 0) {
            return Optional.of("assign proof for '" + fn
                    + "' has a region that matches no branch of its body.");
        }
        if (!covered.add(branchIdx)) {
            return Optional.of("assign proof for '" + fn
                    + "' has two proofs covering the same branch.");
        }
        Branch branch = node.branches().get(branchIdx);
        RefinementValidator.Result result =
                RefinementValidator.validate(node, branch, obligation, refinement);
        if (!result.verified()) {
            return Optional.of("The assign proof for '" + fn
                    + "' does not discharge its granted return for the matched region"
                    + " — strengthen the cuts or weaken the granted return.");
        }
        return Optional.empty();
    }

    private static IrExpr.Match asMatch(IrExpr body) throws CompileException {
        if (body instanceof IrExpr.Match m) {
            return m;
        }
        throw new CompileException(
                "an assign proof body must be a case-function (match)", body.origin());
    }

    /** The proof's region as a conjunction of its refined parameters' predicates (renamed to graph vars). */
    private static List<SymExpr> regionConjuncts(List<IrParam> params) throws CompileException {
        List<SymExpr> region = new ArrayList<>();
        for (IrParam p : params) {
            if (p.sort() instanceof IrSort.Refined refined) {
                SymExpr pred = IrCompiler.compileSymExpr(refined.predicate());
                region.add(Substitute.applySelf(pred, SymExpr.var(p.name() + "_0")));
            }
        }
        return region;
    }

    /**
     * The obligation {@code r_0 OP …} from the granted return, or null for a base
     * sort. {@code @} binds to the result var; any parameter the predicate names
     * (e.g. {@code [Int:d]} → {@code @==d}) is renamed to its graph var first.
     */
    private static SymExpr obligationOf(
            IrSort grantedReturn, String resultVarName, Map<String, SymExpr> rename)
            throws CompileException {
        if (grantedReturn instanceof IrSort.Refined refined) {
            SymExpr pred = Substitute.apply(
                    IrCompiler.compileSymExpr(refined.predicate()), rename);
            return Substitute.applySelf(pred, SymExpr.var(resultVarName));
        }
        return null;
    }

    /**
     * The branch whose first-match effective region equals {@code region}, or -1.
     * Effective region of branch <i>k</i> = {@code guard_k ∧ ¬guard_0 ∧ … ∧
     * ¬guard_{k-1}}; a guardless branch contributes only the complements of the
     * earlier guards (so a trailing {@code [_]} matches the leftover region).
     */
    private static int matchBranch(Node node, List<SymExpr> region) {
        List<Branch> branches = node.branches();
        for (int k = 0; k < branches.size(); k++) {
            List<SymExpr> effective = new ArrayList<>();
            branches.get(k).guard().ifPresent(effective::add);
            boolean ok = true;
            for (int j = 0; j < k; j++) {
                Optional<SymExpr> g = branches.get(j).guard();
                if (g.isEmpty()) {
                    continue;
                }
                if (!(g.get() instanceof SymExpr.Cmp)) {
                    ok = false;  // can't complement a non-comparison guard — skip this branch
                    break;
                }
                effective.add(Refinement.complement(g.get()));
            }
            if (ok && equivalent(region, effective)) {
                return k;
            }
        }
        return -1;
    }

    /** {@code A ⟺ B} over the integer kernel: each side's conjuncts follow from the other. */
    private static boolean equivalent(List<SymExpr> a, List<SymExpr> b) {
        return impliesAll(a, b) && impliesAll(b, a);
    }

    /** Every goal in {@code goals} is dischargeable from {@code hyps} (vacuously true when empty). */
    private static boolean impliesAll(List<SymExpr> hyps, List<SymExpr> goals) {
        for (SymExpr g : goals) {
            if (!BoundAnalysis.discharge(hyps, g)) {
                return false;
            }
        }
        return true;
    }
}
