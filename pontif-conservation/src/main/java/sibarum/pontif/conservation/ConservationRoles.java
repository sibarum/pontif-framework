package sibarum.pontif.conservation;

import sibarum.pontif.conservation.FlowNode.Arm;
import sibarum.pontif.conservation.FlowNode.OpClass;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Role collection over a {@link ConservationGraph}: per <b>branch-path</b>
 * (one choice of arm at every Branch node encountered), per input atom, the
 * multiset of roles — replacing v1's single precedence-picked fates, which
 * collapsed combinations the theory needs (consulted ∧ emitted, multiplicity,
 * verbatim ∧ derived). Fates survive only as display projections derived from
 * these roles.
 */
public final class ConservationRoles {

    private ConservationRoles() {}

    /** One branch-path's accumulated roles. */
    public static final class PathRoles {
        public final String label;
        /** atom → roles (covering semantics applied at query time). */
        public final List<FlowFact> flows = new ArrayList<>();
        public final List<AttributePath> spentInBranching = new ArrayList<>();
        public final List<ResidualFact> residuals = new ArrayList<>();
        /** A residual with an empty touch set — nothing on this path certifies. */
        public boolean poisoned = false;
        /** Callees evaluated on this path (resolved calls only) — NoHalt's input. */
        public final List<String> callees = new ArrayList<>();
        /** A verbatim self re-entry is evaluated on this path — it never halts. */
        public boolean verbatimReentry = false;

        PathRoles(String label) { this.label = label; }

        PathRoles copy(String extraLabel) {
            PathRoles next = new PathRoles(
                    label.isEmpty() ? extraLabel
                            : extraLabel.isEmpty() ? label : label + " > " + extraLabel);
            next.flows.addAll(flows);
            next.spentInBranching.addAll(spentInBranching);
            next.residuals.addAll(residuals);
            next.poisoned = poisoned;
            next.callees.addAll(callees);
            next.verbatimReentry = verbatimReentry;
            return next;
        }
    }

    /**
     * One flow of input content into the return: the source path, whether the
     * chain passed any computation (verbatim vs derived), and the worst class
     * along the chain — CONTENT survives arithmetic; a chain through a
     * MEASUREMENT carries only a bit (the capacity law's pivot).
     */
    public record FlowFact(AttributePath source, boolean verbatim, ChainClass chainClass) {}

    public enum ChainClass { CONTENT, BIT }

    public record ResidualFact(String reason, List<AttributePath> touches) {
        public ResidualFact { touches = List.copyOf(touches); }
    }

    /** Enumerates every branch-path and its roles. */
    public static List<PathRoles> of(ConservationGraph graph) {
        return walk(graph.result(), graph, new Chain(false, ChainClass.CONTENT),
                List.of(new PathRoles("")));
    }

    /** Chain state: has any computation been passed; worst class so far. */
    private record Chain(boolean derived, ChainClass chainClass) {
        Chain through(OpClass opClass) {
            ChainClass next = opClass == OpClass.MEASUREMENT ? ChainClass.BIT : chainClass;
            return new Chain(true, next);
        }
    }

    /**
     * Walks a flow, extending every accumulator in {@code paths}; Branch
     * nodes fork the accumulator list per arm. Reference semantics: a node
     * reached twice contributes its flows twice (multiplicity is per
     * reference — that's what duplication IS); the graph is a DAG by
     * construction so this terminates.
     */
    private static List<PathRoles> walk(
            Flow flow, ConservationGraph graph, Chain chain, List<PathRoles> paths) {
        switch (flow) {
            case Flow.Constant c -> { return paths; }
            case Flow.Verbatim v -> {
                for (PathRoles p : paths) {
                    p.flows.add(new FlowFact(v.path(), !chain.derived(), chain.chainClass()));
                }
                return paths;
            }
            case Flow.Residual r -> {
                for (PathRoles p : paths) {
                    p.residuals.add(new ResidualFact(r.reason(), r.touches()));
                    if (r.touches().isEmpty()) p.poisoned = true;
                }
                return paths;
            }
            case Flow.FromNode n -> {
                FlowNode node = graph.node(n.nodeId());
                ConservationGraph.CallFact call = graph.callFacts().get(n.nodeId());
                if (call != null) {
                    for (PathRoles p : paths) {
                        p.callees.add(call.callee());
                        p.verbatimReentry |= call.verbatimReentry();
                    }
                }
                return switch (node) {
                    case FlowNode.Computation c -> {
                        List<PathRoles> current = paths;
                        Chain through = chain.through(c.opClass());
                        for (Flow input : c.inputs()) {
                            current = walk(input, graph, through, current);
                        }
                        yield current;
                    }
                    case FlowNode.Construction c -> {
                        List<PathRoles> current = paths;
                        for (Flow slot : c.slots().values()) {
                            current = walk(slot, graph, chain, current);
                        }
                        yield current;
                    }
                    case FlowNode.Branch b -> {
                        // Discriminants are SPENT on every arm's path; each
                        // arm forks the accumulators. Calls evaluated inside
                        // the discriminants land on every arm too.
                        List<AttributePath> spent = new ArrayList<>();
                        List<String> discriminantCallees = new ArrayList<>();
                        boolean[] discriminantReentry = {false};
                        for (Flow d : b.discriminants()) {
                            collectPaths(d, graph, new HashSet<>(), spent);
                            collectCalls(d, graph, new HashSet<>(),
                                    discriminantCallees, discriminantReentry);
                        }
                        List<PathRoles> forked = new ArrayList<>();
                        for (Arm arm : b.arms()) {
                            List<PathRoles> armPaths = new ArrayList<>();
                            String armLabel = b.arms().size() > 1 ? arm.label() : "";
                            for (PathRoles p : paths) {
                                PathRoles fork = p.copy(armLabel);
                                fork.spentInBranching.addAll(spent);
                                fork.callees.addAll(discriminantCallees);
                                fork.verbatimReentry |= discriminantReentry[0];
                                armPaths.add(fork);
                            }
                            forked.addAll(walk(arm.result(), graph, chain, armPaths));
                        }
                        yield forked;
                    }
                };
            }
        }
    }

    /** All verbatim paths reachable through a flow (for discriminant spend). */
    private static void collectPaths(
            Flow flow, ConservationGraph graph, Set<String> seen, List<AttributePath> out) {
        switch (flow) {
            case Flow.Verbatim v -> out.add(v.path());
            case Flow.Constant c -> { }
            case Flow.Residual r -> out.addAll(r.touches());
            case Flow.FromNode n -> {
                if (!seen.add(n.nodeId())) return;
                switch (graph.node(n.nodeId())) {
                    case FlowNode.Computation c -> {
                        for (Flow f : c.inputs()) collectPaths(f, graph, seen, out);
                    }
                    case FlowNode.Construction c -> {
                        for (Flow f : c.slots().values()) collectPaths(f, graph, seen, out);
                    }
                    case FlowNode.Branch b -> {
                        for (Flow f : b.discriminants()) collectPaths(f, graph, seen, out);
                        for (Arm a : b.arms()) collectPaths(a.result(), graph, seen, out);
                    }
                }
            }
        }
    }

    /**
     * Calls provably evaluated through a flow — MUST, not MAY, because NoHalt
     * is a positive claim: an over-collected call would let a divergence
     * verdict rest on a call that some path never makes. So a nested
     * multi-arm Branch contributes only its discriminants (always evaluated);
     * its arms are arm-dependent and skipped — a miss, never a false claim.
     * Single-arm branches are irrefutable: the arm always evaluates.
     */
    private static void collectCalls(
            Flow flow, ConservationGraph graph, Set<String> seen,
            List<String> callees, boolean[] reentry) {
        switch (flow) {
            case Flow.Verbatim v -> { }
            case Flow.Constant c -> { }
            case Flow.Residual r -> { }
            case Flow.FromNode n -> {
                if (!seen.add(n.nodeId())) return;
                ConservationGraph.CallFact call = graph.callFacts().get(n.nodeId());
                if (call != null) {
                    callees.add(call.callee());
                    reentry[0] |= call.verbatimReentry();
                }
                switch (graph.node(n.nodeId())) {
                    case FlowNode.Computation c -> {
                        for (Flow f : c.inputs()) {
                            collectCalls(f, graph, seen, callees, reentry);
                        }
                    }
                    case FlowNode.Construction c -> {
                        for (Flow f : c.slots().values()) {
                            collectCalls(f, graph, seen, callees, reentry);
                        }
                    }
                    case FlowNode.Branch b -> {
                        for (Flow f : b.discriminants()) {
                            collectCalls(f, graph, seen, callees, reentry);
                        }
                        if (b.arms().size() == 1) {
                            collectCalls(b.arms().get(0).result(), graph, seen,
                                    callees, reentry);
                        }
                    }
                }
            }
        }
    }

    // --- per-atom queries over one path (covering semantics) ---

    public static int verbatimFlowCount(PathRoles path, AttributePath atom) {
        return (int) path.flows.stream()
                .filter(f -> f.verbatim() && f.source().covers(atom)).count();
    }

    public static boolean hasContentFlow(PathRoles path, AttributePath atom) {
        return path.flows.stream().anyMatch(f ->
                f.chainClass() == ChainClass.CONTENT && f.source().covers(atom));
    }

    public static boolean hasAnyFlow(PathRoles path, AttributePath atom) {
        return path.flows.stream().anyMatch(f -> f.source().covers(atom));
    }

    public static boolean spentInBranching(PathRoles path, AttributePath atom) {
        return path.spentInBranching.stream().anyMatch(p -> p.covers(atom));
    }

    public static boolean feedsResidual(PathRoles path, AttributePath atom) {
        return path.residuals.stream().anyMatch(r ->
                r.touches().stream().anyMatch(p -> p.covers(atom)));
    }
}
