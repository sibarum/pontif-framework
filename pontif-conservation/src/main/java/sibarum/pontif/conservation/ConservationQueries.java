package sibarum.pontif.conservation;

import sibarum.pontif.conservation.ConservationGraph.Capacity;
import sibarum.pontif.conservation.ConservationGraph.TypedAtom;
import sibarum.pontif.conservation.ConservationRoles.PathRoles;

import java.util.List;
import java.util.Optional;

/**
 * Conservation properties over the graph's roles, per the ratified algebra.
 * Everything fails closed on residual flow — what cannot be traced cannot be
 * certified — and the headline property is sort-aware under the capacity law:
 * measurement counts as conservation exactly when it exhausts the measured
 * content.
 */
public final class ConservationQueries {

    private ConservationQueries() {}

    /**
     * Data-Conservative: per branch-path, every Int/Decimal input atom flows
     * (verbatim or derived, content-class) into the return; every Bool atom
     * flows OR is spent in branching (its whole content is one bit); other
     * sorts follow the numeric rule. Empty = holds; otherwise the first
     * violation, named.
     */
    public static Optional<String> dataConservative(ConservationGraph graph) {
        return dataConservativeExcept(graph, null);
    }

    /**
     * As {@link #dataConservative}, with atoms covered by {@code dropped}
     * (when non-null) REQUIRED to carry no content into the return — the
     * declared, stale-checked intentional erasure. Being spent in branching
     * is permitted for dropped atoms (consultation isn't presence).
     */
    public static Optional<String> dataConservativeExcept(
            ConservationGraph graph, AttributePath dropped) {
        if (dropped != null
                && graph.inputs().stream().noneMatch(a -> dropped.covers(a.path()))) {
            return Optional.of("'" + dropped + "' names no input attribute of this function");
        }
        for (PathRoles path : ConservationRoles.of(graph)) {
            if (path.poisoned) {
                return Optional.of(onPath(path, "untraceable flow ("
                        + path.residuals.get(0).reason() + ") — nothing certifies"));
            }
            for (TypedAtom atom : graph.inputs()) {
                if (dropped != null && dropped.covers(atom.path())) {
                    if (ConservationRoles.hasAnyFlow(path, atom.path())) {
                        return Optional.of(onPath(path, "'" + atom.path()
                                + "' is declared dropped but flows into the return — "
                                + "the proof is stale; update or remove it"));
                    }
                    continue;
                }
                if (ConservationRoles.feedsResidual(path, atom.path())) {
                    return Optional.of(onPath(path, "'" + atom.path()
                            + "' feeds untraceable flow — cannot certify"));
                }
                boolean ok = switch (atom.capacity()) {
                    case NUMERIC, OTHER ->
                            ConservationRoles.hasContentFlow(path, atom.path());
                    case BIT -> ConservationRoles.hasAnyFlow(path, atom.path())
                            || ConservationRoles.spentInBranching(path, atom.path());
                };
                if (!ok) {
                    String detail = atom.capacity() == Capacity.BIT
                            ? "neither flows into the return nor is spent in branching"
                            : ConservationRoles.hasAnyFlow(path, atom.path())
                                    ? "reaches the return only as a measurement bit — "
                                            + "content does not"
                                    : ConservationRoles.spentInBranching(path, atom.path())
                                            ? "is only consulted by branching — its content "
                                                    + "never reaches the return"
                                            : "is UNTOUCHED — no flow into the return";
                    return Optional.of(onPath(path, "'" + atom.path() + "' " + detail));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The reversibility witness: a verbatim bijective placement. Multi-arm
     * branches refuse (the derived exit-assertion rule — discriminants
     * conserved + guards partition — is a named follow-up); single-arm
     * branches are irrefutable destructures and pass through.
     */
    public static Optional<String> reversible(ConservationGraph graph) {
        for (FlowNode node : graph.nodes().values()) {
            if (node instanceof FlowNode.Branch b && b.arms().size() > 1) {
                return Optional.of("multi-branch functions are not yet certifiable as "
                        + "reversible (the join must be re-discriminable — a later slice)");
            }
            if (node instanceof FlowNode.Computation) {
                return Optional.of("dataflow includes computation — "
                        + "not a verbatim placement");
            }
        }
        List<PathRoles> paths = ConservationRoles.of(graph);
        for (PathRoles path : paths) {
            if (path.poisoned || !path.residuals.isEmpty()) {
                return Optional.of(onPath(path, "untraceable flow — cannot certify"));
            }
            for (TypedAtom atom : graph.inputs()) {
                int count = ConservationRoles.verbatimFlowCount(path, atom.path());
                if (count != 1) {
                    return Optional.of(onPath(path, "'" + atom.path() + "' is placed "
                            + count + "× (a bijection places every input exactly once)"));
                }
            }
        }
        // Output side: every constructed slot must itself be verbatim (or a
        // nested all-verbatim construction) — single-sourced by construction.
        return verbatimSlots(graph.result(), graph);
    }

    private static Optional<String> verbatimSlots(Flow flow, ConservationGraph graph) {
        return switch (flow) {
            case Flow.Verbatim v -> Optional.empty();
            case Flow.Constant c -> Optional.of(
                    "an output slot is constant-sourced — not a bijective placement");
            case Flow.Residual r -> Optional.of("untraceable output flow");
            case Flow.FromNode n -> switch (graph.node(n.nodeId())) {
                case FlowNode.Construction c -> {
                    for (Flow slot : c.slots().values()) {
                        Optional<String> bad = verbatimSlots(slot, graph);
                        if (bad.isPresent()) yield bad;
                    }
                    yield Optional.empty();
                }
                case FlowNode.Branch b -> {
                    for (FlowNode.Arm arm : b.arms()) {
                        Optional<String> bad = verbatimSlots(arm.result(), graph);
                        if (bad.isPresent()) yield bad;
                    }
                    yield Optional.empty();
                }
                case FlowNode.Computation c -> Optional.of(
                        "an output slot is computed — not a verbatim placement");
            };
        };
    }

    /** Some input atom's content is verbatim-placed more than once on some path. */
    public static boolean duplicated(ConservationGraph graph) {
        for (PathRoles path : ConservationRoles.of(graph)) {
            for (TypedAtom atom : graph.inputs()) {
                if (ConservationRoles.verbatimFlowCount(path, atom.path()) > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Derived display projection of an atom's roles on one path (fates as views). */
    public static String fateView(PathRoles path, TypedAtom atom) {
        if (path.poisoned) return "RESIDUAL (path untraceable)";
        if (ConservationRoles.feedsResidual(path, atom.path())) {
            return "feeds-residual (cannot certify)";
        }
        boolean content = ConservationRoles.hasContentFlow(path, atom.path());
        boolean any = ConservationRoles.hasAnyFlow(path, atom.path());
        boolean spent = ConservationRoles.spentInBranching(path, atom.path());
        int verbatim = ConservationRoles.verbatimFlowCount(path, atom.path());
        if (verbatim > 1) return "placed " + verbatim + "× (duplicated)";
        if (verbatim == 1 && !spent) return "flows-verbatim";
        if (verbatim == 1) return "flows-verbatim + spent-in-branching";
        if (content) return spent ? "flows-derived + spent-in-branching" : "flows-derived";
        if (any) return "measurement-bit only" + (spent ? " + spent-in-branching" : "");
        if (spent) return "spent-in-branching (content not in return)";
        return "UNTOUCHED (no flow into the return)";
    }

    private static String onPath(PathRoles path, String message) {
        return path.label.isEmpty() ? message
                : message + "  [path: " + path.label + "]";
    }
}
