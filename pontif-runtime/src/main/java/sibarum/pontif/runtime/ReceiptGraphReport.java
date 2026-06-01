package sibarum.pontif.runtime;

import sibarum.pontif.ir.AliasResolver;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.AltParser;
import sibarum.pontif.parser.ParseException;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.receipts.BuiltinIssuer;
import sibarum.pontif.receipts.ClosingReceipt;
import sibarum.pontif.receipts.Drafter;
import sibarum.pontif.receipts.GraphReference;
import sibarum.pontif.receipts.Node;
import sibarum.pontif.receipts.Notary;
import sibarum.pontif.receipts.ProofBinding;
import sibarum.pontif.receipts.ReceiptGraph;
import sibarum.pontif.receipts.ReceiptGraphPrinter;
import sibarum.pontif.receipts.Refinement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Produces a reviewable text artifact for a program's receipt-graph: the
 * graph itself plus what the built-in default issuer discharged and how
 * the notary verdicts came out. The "show me the receipts" debug view —
 * a developer-facing window into the proof process, emitted as plain text
 * (per the project decision to favor text artifacts over visualization).
 *
 * <p>Pipeline: alt-syntax source → {@link AltParser} → {@link AliasResolver}
 * → {@link Drafter} → {@link ReceiptGraphPrinter}, with {@link BuiltinIssuer}
 * + {@link Notary} layered on for the closing-receipt section. The
 * alias-resolution step mirrors {@code IrCompiler.compile} so struct-typed
 * params draft correctly.
 */
public final class ReceiptGraphReport {

    private ReceiptGraphReport() {}

    /** Outcome of report generation: the text, or a parse/compile error. */
    public sealed interface Result permits Result.Generated, Result.Failed {
        record Generated(String text) implements Result {}
        record Failed(String error) implements Result {}
    }

    /**
     * Drafts and renders the receipt-graph report for alt-syntax source.
     * Never throws — parse/compile failures come back as
     * {@link Result.Failed}.
     */
    public static Result fromAltSource(String source, String sourceName) {
        IrModule parsed;
        try {
            parsed = AltParser.parseModule(source, sourceName);
        } catch (ParseException pe) {
            return new Result.Failed("Parse error: " + pe.getMessage());
        } catch (RuntimeException e) {
            return new Result.Failed("Parse error: " + e.getMessage());
        }
        try {
            IrModule resolved = AliasResolver.resolve(parsed);
            ReceiptGraph graph = Drafter.draft(resolved);
            return new Result.Generated(render(sourceName, resolved, graph));
        } catch (CompileException ce) {
            return new Result.Failed("Compile error: " + ce.getMessage());
        }
    }

    /**
     * Writes the report to {@code dir/baseName.receipts.txt}, creating the
     * directory if needed. Failures are written as the file body so the
     * artifact always exists for review. Returns the written path.
     */
    public static Path writeReport(Path dir, String baseName, String source, String sourceName)
            throws IOException {
        String body = switch (fromAltSource(source, sourceName)) {
            case Result.Generated g -> g.text();
            case Result.Failed f -> "# Receipt-graph report: " + sourceName + "\n\n"
                    + f.error() + "\n";
        };
        Files.createDirectories(dir);
        Path out = dir.resolve(baseName + ".receipts.txt");
        Files.writeString(out, body);
        return out;
    }

    // --- Rendering ---------------------------------------------------------

    private static String render(String sourceName, IrModule module, ReceiptGraph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Receipt-graph report: ").append(sourceName).append("\n\n");
        sb.append(ReceiptGraphPrinter.print(graph));

        // In-source proofs rescue branches the built-in engine can't close, so
        // the report agrees with the return gate (same ProofBinding). Binding
        // problems are skipped here — the report is diagnostic, not a gate.
        Map<GraphReference, Refinement> proofs = ProofBinding.bind(module, graph).proofs();

        // Obligations: every per-branch claim the issuer considered, with
        // outcome — including the ones it COULDN'T discharge, so a tightened
        // return refinement that fails is visible rather than silent.
        sb.append("\n## Obligations -- issuer ").append(BuiltinIssuer.ISSUER_ID).append("\n");
        List<BuiltinIssuer.Attempt> attempts = BuiltinIssuer.attemptAll(graph, proofs);
        List<Node> nodes = graph.roots();
        for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
            Node node = nodes.get(nodeIndex);
            final int ni = nodeIndex;
            List<BuiltinIssuer.Attempt> nodeAttempts =
                    attempts.stream().filter(a -> a.nodeIndex() == ni).toList();

            if (nodeAttempts.isEmpty()) {
                sb.append("  ").append(node.functionName())
                        .append("  (no return refinement -- nothing to prove)\n");
                continue;
            }

            sb.append("  ").append(node.functionName()).append("  :  ")
                    .append(ReceiptGraphPrinter.renderSym(nodeAttempts.get(0).obligation()))
                    .append("\n");
            for (BuiltinIssuer.Attempt a : nodeAttempts) {
                renderAttempt(sb, graph, node, a);
            }
        }
        return sb.toString();
    }

    /**
     * Renders one branch's attempt: the branch header (with guard, if any),
     * the hypotheses in scope, the substituted goal the engine actually
     * tried to prove, and the outcome (discharged + notary verdict, or
     * honest NOT DISCHARGED). Multi-line indented form — vertical space
     * costs less than scannability.
     */
    private static void renderAttempt(
            StringBuilder sb, ReceiptGraph graph, Node node, BuiltinIssuer.Attempt a) {
        sb.append("      branch ").append(a.branchIndex());
        node.branches().get(a.branchIndex()).guard().ifPresent(g ->
                sb.append(" [").append(ReceiptGraphPrinter.renderSym(g)).append("]"));
        sb.append("\n");

        if (a.hypotheses().isEmpty()) {
            sb.append("        hypotheses: (none)\n");
        } else {
            sb.append("        hypotheses: ")
                    .append(a.hypotheses().stream()
                            .map(ReceiptGraphPrinter::renderSym)
                            .collect(Collectors.joining(", ")))
                    .append("\n");
        }
        // Only show the goal line when substitution actually changed something —
        // otherwise it's just a restatement of the obligation header above.
        SymExpr goalRendered = a.substitutedGoal();
        if (!goalRendered.equals(a.obligation())) {
            sb.append("        goal: ")
                    .append(ReceiptGraphPrinter.renderSym(goalRendered))
                    .append("\n");
        }
        if (a.discharged()) {
            String issuer = a.provenByRefinement()
                    ? BuiltinIssuer.REFINEMENT_ISSUER_ID : BuiltinIssuer.ISSUER_ID;
            ClosingReceipt cr = new ClosingReceipt(
                    issuer, a.obligation(),
                    new GraphReference(a.nodeIndex(), a.branchIndex()), java.util.Map.of());
            Notary.Verdict v = Notary.hypothesisSupported(graph, cr);
            sb.append("        -> discharged ")
                    .append(a.provenByRefinement() ? "[via proof; notary: " : "[notary: ")
                    .append(v.accepted() ? "accepted" : "REJECTED").append("]\n");
        } else {
            sb.append("        -> NOT DISCHARGED (beyond the default issuer; runtime check remains)\n");
        }
    }
}
