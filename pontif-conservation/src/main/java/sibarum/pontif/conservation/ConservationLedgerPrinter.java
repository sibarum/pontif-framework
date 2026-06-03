package sibarum.pontif.conservation;

import sibarum.pontif.conservation.ConservationLedger.ConservationBranch;
import sibarum.pontif.conservation.ConservationLedger.ConservationNode;
import sibarum.pontif.conservation.ConservationLedger.NamedSort;
import sibarum.pontif.conservation.ConservationQueries.InputFate;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders a {@link ConservationLedger} as reviewable text — the deliverable
 * James reads to design the query surface. Event lines preserve order
 * (sequence is load-bearing); the per-branch classification block is the
 * derived reading queries are built from. UNTOUCHED is shouted: it's the
 * silent-loss candidate. OPAQUE is shouted too: honest ignorance, on which
 * every conservation assertion fails closed.
 */
public final class ConservationLedgerPrinter {

    private ConservationLedgerPrinter() {}

    public static String print(ConservationLedger ledger) {
        return ledger.nodes().stream()
                .map(ConservationLedgerPrinter::printNode)
                .collect(Collectors.joining("\n"));
    }

    public static String printNode(ConservationNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append(node.functionName()).append('(')
          .append(node.params().stream()
                  .map(p -> p.name() + ": " + p.sortRendering())
                  .collect(Collectors.joining(", ")))
          .append(") -> r_0: ").append(node.returnRendering()).append('\n');
        sb.append("  inputs:  ").append(renderPaths(node.inputs())).append('\n');
        sb.append("  outputs: ").append(renderPaths(node.outputs())).append('\n');
        for (ConservationBranch branch : node.branches()) {
            printBranch(node, branch, sb);
        }
        return sb.toString();
    }

    private static void printBranch(
            ConservationNode node, ConservationBranch branch, StringBuilder sb) {
        String header = branch.guard().map(g -> "[" + renderGuard(g) + "]")
                .orElseGet(() -> branch.patternNote().orElse("unconditional"));
        sb.append("  branch (").append(header).append("):\n");
        for (Event event : branch.events()) {
            sb.append("    ").append(renderEvent(event)).append('\n');
        }
        sb.append("    classification:\n");
        for (AttributePath atom : node.inputs()) {
            InputFate fate = ConservationQueries.fateOf(branch, atom);
            sb.append("      ").append(pad(atom.toString(), 16)).append(' ')
              .append(renderFate(fate)).append('\n');
        }
    }

    private static String renderEvent(Event event) {
        return switch (event) {
            case Event.Consult c -> "consult: " + renderPaths(c.subjects()) + "   (guard)";
            case Event.Combine c -> "combine: "
                    + c.operands().stream().map(Provenance::render)
                            .collect(Collectors.joining(" " + c.op() + " "))
                    + " -> " + c.id();
            case Event.Emit e -> "emit:    " + e.source().render() + " -> " + e.target()
                    + switch (e.source()) {
                        case Provenance.Path p -> "   [verbatim]";
                        case Provenance.Derived d -> "   [derived]";
                        case Provenance.Constant k -> "   [constant]";
                        case Provenance.CallResult c -> "   [call result — untraced]";
                        case Provenance.Opaque o -> "   [OPAQUE]";
                    };
            case Event.Call c -> "call:    " + c.target() + "("
                    + c.args().stream().map(Provenance::render)
                            .collect(Collectors.joining(", "))
                    + ") -> " + c.id() + "   (by reference; summary substitution is a later slice)";
            case Event.Opaque o -> "OPAQUE:  " + o.reason()
                    + (o.touched().isEmpty()
                            ? "   (untraceable — poisons the whole branch)"
                            : "   touches " + renderPaths(o.touched()));
        };
    }

    private static String renderFate(InputFate fate) {
        return switch (fate) {
            case EMITTED_VERBATIM -> "emitted-verbatim";
            case FLOWS_DERIVED -> "flows-derived";
            case VIA_CALL -> "via-call (unproven in v1)";
            case CONSULTED_ONLY -> "consulted-only (content not in output)";
            case UNTOUCHED -> "UNTOUCHED (no flow into any output)";
            case OPAQUE -> "OPAQUE (untraceable in v1)";
        };
    }

    /**
     * Compact infix rendering for guard predicates (the shapes guards take:
     * comparisons, conjunctions, paths, literals). Falls back to toString for
     * anything else — guards are display-only here.
     */
    private static String renderGuard(sibarum.pontif.core.symbolic.SymExpr e) {
        return switch (e) {
            case sibarum.pontif.core.symbolic.SymExpr.Var v -> v.name();
            case sibarum.pontif.core.symbolic.SymExpr.Lit l -> String.valueOf(l.value());
            case sibarum.pontif.core.symbolic.SymExpr.Dec d -> d.value().toPlainString();
            case sibarum.pontif.core.symbolic.SymExpr.Bool b -> String.valueOf(b.value());
            case sibarum.pontif.core.symbolic.SymExpr.Cmp c ->
                    renderGuard(c.left()) + " " + switch (c.op()) {
                        case LT -> "<"; case LE -> "<="; case GT -> ">";
                        case GE -> ">="; case EQ -> "=="; case NE -> "!=";
                    } + " " + renderGuard(c.right());
            case sibarum.pontif.core.symbolic.SymExpr.And a ->
                    renderGuard(a.left()) + " & " + renderGuard(a.right());
            case sibarum.pontif.core.symbolic.SymExpr.Or o ->
                    "(" + renderGuard(o.left()) + " | " + renderGuard(o.right()) + ")";
            case sibarum.pontif.core.symbolic.SymExpr.Add a ->
                    renderGuard(a.left()) + " + " + renderGuard(a.right());
            case sibarum.pontif.core.symbolic.SymExpr.Mul m ->
                    renderGuard(m.left()) + " * " + renderGuard(m.right());
            case sibarum.pontif.core.symbolic.SymExpr.FieldAccess fa ->
                    renderGuard(fa.base()) + "." + fa.fieldName();
            default -> e.toString();
        };
    }

    private static String renderPaths(List<AttributePath> paths) {
        return paths.stream().map(AttributePath::toString).collect(Collectors.joining(", "));
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
