package sibarum.pontif.runtime;

import sibarum.pontif.conservation.ConservationDrafter;
import sibarum.pontif.conservation.ConservationGraph.Ledger;
import sibarum.pontif.conservation.NoHalt;
import sibarum.pontif.ir.AliasResolver;
import sibarum.pontif.ir.AlgebraicCheck;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrStmt;
import sibarum.pontif.ir.MethodResolver;
import sibarum.pontif.parser.AltParser;
import sibarum.pontif.parser.ParseException;
import sibarum.pontif.receipts.BuiltinIssuer;
import sibarum.pontif.receipts.Drafter;
import sibarum.pontif.receipts.GradientAnalysis;
import sibarum.pontif.receipts.GraphReference;
import sibarum.pontif.receipts.Node;
import sibarum.pontif.receipts.ProofBinding;
import sibarum.pontif.receipts.ReceiptGraph;
import sibarum.pontif.receipts.Refinement;
import sibarum.pontif.receipts.ReturnProofBinding;
import sibarum.pontif.runtime.module.AlgebraExtension;
import sibarum.pontif.runtime.module.ModuleResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The per-function <b>classification dossier</b> — Step 2 of the receipt-graph
 * overhaul (docs/receipt-graph-overhaul.md). Assembles, for every function in a
 * module, the proof-relevant facts the subsystem already derives, in one view:
 *
 * <ul>
 *   <li><b>halting</b> — does the module-wide {@link NoHalt} greatest fixpoint
 *       prove this function never completes?</li>
 *   <li><b>algebraic</b> — is the body in the algebraic fragment? Discovered
 *       <em>automatically</em> as the greatest fixpoint over
 *       {@link AlgebraicCheck#isAlgebraic} (no {@code assign proof} annotation
 *       required — the classifier figures it out).</li>
 *   <li><b>receipts</b> — do the function's return obligations discharge (the
 *       built-in issuer + supplied proofs, including {@code assign proof}
 *       grants), or are some open?</li>
 * </ul>
 *
 * <p><b>Read-only.</b> This slice only <em>reports</em> — it changes no gate and
 * runs for every function unconditionally (the decision-free half of the
 * overhaul's "discovery runs for all; gating stays opt-in" ruling). It is the
 * seam the eventual unified {@code classify(fn)} object grows from; today it
 * simply co-locates the existing analyses so their agreement (or disagreement)
 * is visible.
 */
public final class ClassificationReport {

    private ClassificationReport() {}

    /** A function's assembled classification across the proof dimensions. */
    public record FunctionClassification(
            String name,
            boolean algebraic,
            Optional<String> divergence,   // NoHalt witness, if provably non-halting
            String receipts,               // human-readable return-obligation status
            GradientAnalysis.Result gradient) {}  // effective-sort trajectory under iteration

    /** Outcome of report generation: the dossiers + rendered text, or an error. */
    public sealed interface Result permits Result.Generated, Result.Failed {
        record Generated(List<FunctionClassification> classifications, String text) implements Result {}
        record Failed(String error) implements Result {}
    }

    /** Classifies every function in alt-syntax source. Never throws. */
    public static Result fromAltSource(String source, String sourceName) {
        return fromAltSource(source, sourceName, null);
    }

    public static Result fromAltSource(String source, String sourceName, Path resolveDir) {
        IrModule parsed;
        try {
            parsed = AltParser.parseModule(source, sourceName);
        } catch (ParseException | RuntimeException e) {
            return new Result.Failed("Parse error: " + e.getMessage());
        }
        try {
            // Same resolve/link chain the receipt and conservation reports use,
            // so all three views agree about the module under examination.
            IrModule linked = ModuleResolver.resolveAndCombine(parsed, resolveDir, sourceName);
            IrModule module = AliasResolver.resolve(MethodResolver.resolve(linked));

            ReceiptGraph graph = Drafter.draft(module);
            Ledger ledger = ConservationDrafter.draft(module);
            Map<String, String> divergences = NoHalt.of(ledger);
            Set<String> algebraic = discoverAlgebraic(module);
            Map<String, String> receiptStatus = receiptStatusByFunction(module, graph);

            // First receipt node per function name (a non-$iter$ root) — the
            // graph the gradient reads its recursive step off.
            Map<String, Node> nodeByName = new LinkedHashMap<>();
            for (Node n : graph.roots()) nodeByName.putIfAbsent(n.functionName(), n);

            List<FunctionClassification> out = new ArrayList<>();
            for (String name : functionNamesInSourceOrder(module)) {
                Node node = nodeByName.get(name);
                GradientAnalysis.Result gradient = node != null
                        ? GradientAnalysis.of(node)
                        : new GradientAnalysis.Result(
                                GradientAnalysis.Gradient.NON_RECURSIVE, "no receipt node");
                out.add(new FunctionClassification(
                        name,
                        algebraic.contains(name),
                        Optional.ofNullable(divergences.get(name)),
                        receiptStatus.getOrDefault(name, "no return obligation"),
                        gradient));
            }
            return new Result.Generated(out, render(sourceName, out));
        } catch (CompileException ce) {
            return new Result.Failed("Compile error: " + ce.getMessage());
        }
    }

    /** Writes {@code dir/baseName.classification.txt}; returns the path. */
    public static Path writeReport(Path dir, String baseName, String source, String sourceName)
            throws IOException {
        String body = switch (fromAltSource(source, sourceName)) {
            case Result.Generated g -> g.text();
            case Result.Failed f -> "# Classification report: " + sourceName + "\n\n" + f.error() + "\n";
        };
        Files.createDirectories(dir);
        Path out = dir.resolve(baseName + ".classification.txt");
        Files.writeString(out, body);
        return out;
    }

    // --- Automatic algebraic discovery (greatest fixpoint) -----------------

    /**
     * The set of functions whose bodies are algebraic — discovered, not declared.
     * Greatest fixpoint: assume every (non-overloaded) function algebraic, then
     * repeatedly drop any whose body leaves the fragment given the current set,
     * until stable. A call is algebraic only to a still-surviving function (or a
     * primitive), so a function calling a dropped one is dropped in turn.
     */
    private static Set<String> discoverAlgebraic(IrModule module) {
        Map<String, IrStmt.FunctionDecl> decls = new LinkedHashMap<>();
        Set<String> overloaded = new LinkedHashSet<>();
        for (IrStmt s : module.statements()) {
            if (s instanceof IrStmt.FunctionDecl fd) {
                if (decls.containsKey(fd.name())) overloaded.add(fd.name());
                decls.put(fd.name(), fd);
            }
        }
        // Overloaded names aren't supported by the fragment probe's single-decl
        // world — exclude them (documented limitation, matches AlgebraicCheck).
        Set<String> world = new LinkedHashSet<>(decls.keySet());
        world.removeAll(overloaded);

        boolean changed = true;
        while (changed) {
            changed = false;
            for (String name : new ArrayList<>(world)) {
                // A function survives only if its body is in the fragment given
                // the current survivors AND it is not part of a recursive cycle
                // among them — the algebraic call graph must be acyclic (a
                // recursive arithmetic body is not algebraic), matching
                // AlgebraicCheck's rule for a claimed set. Both conditions
                // reference the current `world`, so dropping one member (a cycle
                // or a fragment violation) cascades to its callers next pass.
                boolean ok = AlgebraicCheck.isAlgebraic(decls.get(name), world,
                                AlgebraExtension.ALGEBRAIC_PRIMITIVES)
                        && !reachesSelf(name, decls, world);
                if (!ok) {
                    world.remove(name);
                    changed = true;
                }
            }
        }
        return world;
    }

    /** Whether {@code start} can reach itself through calls that stay within {@code world} (a cycle). */
    private static boolean reachesSelf(
            String start, Map<String, IrStmt.FunctionDecl> decls, Set<String> world) {
        Set<String> seen = new LinkedHashSet<>();
        java.util.ArrayDeque<String> frontier = new java.util.ArrayDeque<>(
                calleesWithin(decls.get(start), world));
        while (!frontier.isEmpty()) {
            String f = frontier.poll();
            if (f.equals(start)) return true;
            if (!seen.add(f) || !world.contains(f)) continue;
            frontier.addAll(calleesWithin(decls.get(f), world));
        }
        return false;
    }

    /** Names this function's body calls that are themselves in {@code world}. */
    private static Set<String> calleesWithin(IrStmt.FunctionDecl fd, Set<String> world) {
        Set<String> out = new LinkedHashSet<>();
        collectCalls(fd.body(), out);
        out.retainAll(world);
        return out;
    }

    private static void collectCalls(IrExpr e, Set<String> out) {
        switch (e) {
            case IrExpr.Call c -> {
                out.add(c.functionName());
                for (IrExpr a : c.args()) collectCalls(a, out);
            }
            case IrExpr.BinOp op -> { collectCalls(op.left(), out); collectCalls(op.right(), out); }
            case IrExpr.LetIn l -> { collectCalls(l.value(), out); collectCalls(l.body(), out); }
            case IrExpr.FieldAccess fa -> collectCalls(fa.base(), out);
            case IrExpr.Record r -> { for (IrExpr v : r.members().values()) collectCalls(v, out); }
            case IrExpr.Match m -> {
                collectCalls(m.scrutinee(), out);
                for (IrExpr.MatchBranch b : m.branches()) collectCalls(b.result(), out);
            }
            default -> { }  // leaves / forms an algebraic body never contains
        }
    }

    // --- Receipt-obligation status per function ----------------------------

    /**
     * Maps each function to a one-line return-obligation verdict: whether its
     * declared-refinement obligations and its {@code assign proof} grants all
     * discharge, some stay open, or there is nothing to prove. Built from the
     * same issuer + proof binders the receipt report renders.
     */
    private static Map<String, String> receiptStatusByFunction(IrModule module, ReceiptGraph graph) {
        Map<GraphReference, Refinement> proofs = ProofBinding.bind(module, graph).proofs();
        List<BuiltinIssuer.Attempt> attempts = BuiltinIssuer.attemptAll(graph, proofs);

        List<IrStmt.ReturnProof> returnProofs = new ArrayList<>();
        for (IrStmt s : module.statements()) {
            if (s instanceof IrStmt.ReturnProof rp) returnProofs.add(rp);
        }
        List<ReturnProofBinding.Bound> bounds = ReturnProofBinding.bind(returnProofs, graph);

        List<Node> nodes = graph.roots();
        Map<String, int[]> tally = new LinkedHashMap<>();  // name -> {obligations, discharged}
        for (BuiltinIssuer.Attempt a : attempts) {
            String fn = nodes.get(a.nodeIndex()).functionName();
            int[] t = tally.computeIfAbsent(fn, k -> new int[2]);
            t[0]++;
            if (a.discharged()) t[1]++;
        }
        for (ReturnProofBinding.Bound b : bounds) {
            String fn = nodes.get(b.nodeIndex()).functionName();
            int[] t = tally.computeIfAbsent(fn, k -> new int[2]);
            t[0]++;
            if (b.discharged()) t[1]++;
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : tally.entrySet()) {
            int total = e.getValue()[0], ok = e.getValue()[1];
            out.put(e.getKey(), ok == total
                    ? "proved (" + total + (total == 1 ? " obligation)" : " obligations)")
                    : "open (" + ok + "/" + total + " discharged)");
        }
        return out;
    }

    // --- Rendering ---------------------------------------------------------

    private static List<String> functionNamesInSourceOrder(IrModule module) {
        Set<String> names = new LinkedHashSet<>();  // dedup overloads, keep source order
        for (IrStmt s : module.statements()) {
            if (s instanceof IrStmt.FunctionDecl fd) names.add(fd.name());
        }
        return new ArrayList<>(names);
    }

    private static String render(String sourceName, List<FunctionClassification> cls) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Classification report: ").append(sourceName).append("\n\n");
        if (cls.isEmpty()) {
            sb.append("(no functions)\n");
            return sb.toString();
        }
        for (FunctionClassification c : cls) {
            sb.append(c.name()).append("\n");
            sb.append("  halting:    ").append(c.divergence()
                    .map(w -> "provably never halts -- " + w)
                    .orElse("no divergence proof (may or may not halt)")).append("\n");
            sb.append("  algebraic:  ").append(c.algebraic() ? "yes" : "no").append("\n");
            sb.append("  receipts:   ").append(c.receipts()).append("\n");
            GradientAnalysis.Result g = c.gradient();
            if (g.gradient() != GradientAnalysis.Gradient.NON_RECURSIVE) {
                sb.append("  gradient:   ")
                        .append(g.gradient().name().toLowerCase())
                        .append(" -- ").append(g.detail()).append("\n");
            }
        }
        return sb.toString();
    }
}
