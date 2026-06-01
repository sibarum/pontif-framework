package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrParam;
import sibarum.pontif.ir.IrStmt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binds in-source {@code proof f = …} declarations to the receipt-graph
 * obligations they discharge, translating each struct-literal proof tree to a
 * {@link Refinement} keyed by {@link GraphReference}.
 *
 * <p>Shared by the two consumers so they can never disagree about what a proof
 * proves: the return-refinement gate ({@code PontifCompiler}) — which turns any
 * {@link Result#problems()} into a hard compile error — and the receipt-graph
 * report ({@code ReceiptGraphReport}) — which renders proof-discharged branches
 * and treats problems as best-effort skips.
 *
 * <p>Variable renaming matches the {@link Drafter}: a source parameter {@code x}
 * is the graph variable {@code x_0}, so proof predicates authored over {@code x}
 * align with the obligation's {@code PathFacts}.
 *
 * <p>v1 binds a proof only to a function that is the sole node of its name
 * (no overloads) with a single branch (no {@code match}); anything else is a
 * reported problem.
 */
public final class ProofBinding {

    private ProofBinding() {}

    /**
     * @param proofs   valid proofs, keyed by the obligation they discharge
     * @param problems human-readable issues (unknown/overloaded/orphaned/
     *                 multi-branch target, duplicate proof, untranslatable tree),
     *                 in source order; empty when every proof bound cleanly
     */
    public record Result(Map<GraphReference, Refinement> proofs, List<String> problems) {
        public Result {
            proofs = Map.copyOf(proofs);
            problems = List.copyOf(problems);
        }
    }

    /**
     * Builds the proof map for {@code module} against its already-drafted
     * {@code graph}. Never throws — translation failures become problems.
     */
    public static Result bind(IrModule module, ReceiptGraph graph) {
        Map<String, IrStmt.Proof> proofDecls = new LinkedHashMap<>();
        Map<String, IrStmt.FunctionDecl> fnDecls = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.Proof p) {
                if (proofDecls.put(p.functionName(), p) != null) {
                    problems.add("Duplicate proof for '" + p.functionName()
                            + "' — at most one proof per function.");
                }
            } else if (stmt instanceof IrStmt.FunctionDecl fd) {
                fnDecls.putIfAbsent(fd.name(), fd);
            }
        }

        // function name → node index/indices (one node per declaration).
        Map<String, List<Integer>> nodeIdxByName = new HashMap<>();
        for (int i = 0; i < graph.roots().size(); i++) {
            nodeIdxByName.computeIfAbsent(graph.roots().get(i).functionName(),
                    k -> new ArrayList<>()).add(i);
        }

        Map<GraphReference, Refinement> proofs = new HashMap<>();
        for (Map.Entry<String, IrStmt.Proof> e : proofDecls.entrySet()) {
            String fn = e.getKey();
            List<Integer> idxs = nodeIdxByName.get(fn);
            if (idxs == null || idxs.isEmpty()) {
                problems.add("Proof references unknown function '" + fn + "'.");
                continue;
            }
            if (idxs.size() > 1) {
                problems.add("Proof for '" + fn + "' targets an overloaded function — "
                        + "proofs on overloaded functions aren't supported yet.");
                continue;
            }
            int nodeIdx = idxs.get(0);
            Node node = graph.roots().get(nodeIdx);
            if (!node.resultVar().sort().isRefined()) {
                problems.add("Proof for '" + fn + "' is orphaned — '" + fn
                        + "' has no refined return to prove; remove the proof.");
                continue;
            }
            if (node.branches().size() != 1) {
                problems.add("Proof for '" + fn + "' targets a multi-branch (match) "
                        + "function — per-branch proofs aren't supported yet.");
                continue;
            }
            Map<String, SymExpr> rename = new HashMap<>();
            IrStmt.FunctionDecl fd = fnDecls.get(fn);
            if (fd != null) {
                for (IrParam p : fd.params()) {
                    rename.put(p.name(), SymExpr.var(p.name() + "_0"));
                }
            }
            try {
                proofs.put(new GraphReference(nodeIdx, 0),
                        RefinementProof.fromIr(e.getValue().proofTree(), rename));
            } catch (CompileException ce) {
                problems.add("Proof for '" + fn + "' could not be used: " + ce.getMessage());
            }
        }
        return new Result(proofs, problems);
    }
}
