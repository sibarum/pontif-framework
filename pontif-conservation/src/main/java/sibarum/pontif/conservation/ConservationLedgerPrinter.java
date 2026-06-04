package sibarum.pontif.conservation;

import sibarum.pontif.conservation.ConservationGraph.Ledger;
import sibarum.pontif.conservation.ConservationGraph.TypedAtom;
import sibarum.pontif.conservation.ConservationRoles.PathRoles;
import sibarum.pontif.conservation.FlowNode.Arm;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Renders a conservation graph as reviewable text: the node table (the three
 * ruled kinds, with op-class/recoverability verdicts as metadata), then the
 * per-path classification — fates demoted to derived display views over the
 * role multisets. UNTOUCHED and residuals still shout.
 */
public final class ConservationLedgerPrinter {

    private ConservationLedgerPrinter() {}

    public static String print(Ledger ledger) {
        Map<String, String> noHalt = NoHalt.of(ledger);
        return ledger.graphs().stream()
                .map(g -> printNode(g, noHalt.get(g.functionName()), noHalt.keySet()))
                .collect(Collectors.joining("\n"));
    }

    public static String printNode(ConservationGraph graph) {
        return printNode(graph, null, Set.of());
    }

    /**
     * {@code noHaltWitness} is the function-level No-Halt sentence (null = no
     * claim); {@code neverHalting} is the ledger-wide never-halting set, so
     * per-path markers can also name calls into it. The single-graph overload
     * passes the empty set — verbatim re-entry markers are graph-local and
     * still show.
     */
    public static String printNode(ConservationGraph graph,
            String noHaltWitness, Set<String> neverHalting) {
        StringBuilder sb = new StringBuilder();
        sb.append(graph.functionName()).append('(').append(graph.paramsRendering())
          .append(") -> r_0: ").append(graph.returnRendering()).append('\n');
        sb.append("  inputs:  ").append(graph.inputs().stream()
                .map(a -> a.path() + capacityTag(a))
                .collect(Collectors.joining(", "))).append('\n');
        sb.append("  outputs: ").append(graph.outputs().stream()
                .map(AttributePath::toString)
                .collect(Collectors.joining(", "))).append('\n');

        for (FlowNode node : graph.nodes().values()) {
            renderNode(node, sb);
        }
        sb.append("  result:  ").append(graph.result().render()).append('\n');
        if (noHaltWitness != null) {
            sb.append("  no-halt: ").append(noHaltWitness).append('\n');
        }

        List<PathRoles> paths = ConservationRoles.of(graph);
        for (PathRoles path : paths) {
            sb.append("  classification");
            if (!path.label.isEmpty()) sb.append(" [").append(path.label).append(']');
            if (NoHalt.pathDiverges(path, neverHalting)) {
                sb.append(" — never halts (").append(NoHalt.pathWitness(
                        path, graph.functionName(), neverHalting)).append(')');
            }
            sb.append(":\n");
            for (TypedAtom atom : graph.inputs()) {
                sb.append("    ").append(pad(atom.path().toString(), 16)).append(' ')
                  .append(ConservationQueries.fateView(path, atom)).append('\n');
            }
        }
        return sb.toString();
    }

    private static void renderNode(FlowNode node, StringBuilder sb) {
        switch (node) {
            case FlowNode.Computation c -> {
                // Binary operators render infix; composed/unary computations
                // ("via callee") render call-style.
                String body = c.inputs().size() == 2 && c.op().length() <= 2
                        ? c.inputs().stream().map(Flow::render)
                                .collect(Collectors.joining(" " + c.op() + " "))
                        : c.op() + "(" + c.inputs().stream().map(Flow::render)
                                .collect(Collectors.joining(", ")) + ")";
                sb.append("  ").append(pad(c.id() + ":", 7)).append(body)
                  .append("   [").append(c.opClass().name().toLowerCase())
                  .append(", ").append(c.recoverability().name()
                          .toLowerCase().replace('_', '-'))
                  .append("]\n");
            }
            case FlowNode.Branch b -> {
                sb.append("  ").append(pad(b.id() + ":", 7)).append("branch");
                if (!b.discriminants().isEmpty()) {
                    sb.append(" on ").append(b.discriminants().stream()
                            .map(Flow::render).collect(Collectors.joining(", ")));
                } else {
                    sb.append(" (irrefutable)");
                }
                sb.append('\n');
                for (Arm arm : b.arms()) {
                    sb.append("           [").append(arm.label()).append("] -> ")
                      .append(arm.result().render()).append('\n');
                }
            }
            case FlowNode.Construction c -> sb.append("  ").append(pad(c.id() + ":", 7))
                    .append("construct ").append(c.claim()).append(" { ")
                    .append(c.slots().entrySet().stream()
                            .map(e -> e.getKey() + " <- " + e.getValue().render())
                            .collect(Collectors.joining(", ")))
                    .append(" }\n");
        }
    }

    private static String capacityTag(TypedAtom atom) {
        return switch (atom.capacity()) {
            case BIT -> " [bit]";
            case NUMERIC, OTHER -> "";
        };
    }

    /**
     * Compact infix rendering for guard predicates — shared with the drafter's
     * arm labels.
     */
    public static String renderGuard(sibarum.pontif.core.symbolic.SymExpr e) {
        return switch (e) {
            case sibarum.pontif.core.symbolic.SymExpr.Var v -> v.name();
            case sibarum.pontif.core.symbolic.SymExpr.Lit l -> String.valueOf(l.value());
            case sibarum.pontif.core.symbolic.SymExpr.Dec d -> d.value().toPlainString();
            case sibarum.pontif.core.symbolic.SymExpr.Chr c ->
                    "'" + sibarum.pontif.core.types.CharValue.render(c.codePoint()) + "'";
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

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
