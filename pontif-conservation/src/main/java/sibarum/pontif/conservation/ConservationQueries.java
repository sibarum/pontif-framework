package sibarum.pontif.conservation;

import sibarum.pontif.conservation.ConservationLedger.ConservationBranch;
import sibarum.pontif.conservation.ConservationLedger.ConservationNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Programmatic queries over the conservation ledger — the v1 of the
 * query-assert paradigm, deliberately Java-level: the surface proof syntax is
 * designed AFTER the printed data has been reviewed. Every query fails closed
 * on {@link Event.Opaque} and on flow that only reaches a {@link Event.Call}
 * (callee summaries are a later slice): what cannot be traced cannot be
 * asserted conserved.
 */
public final class ConservationQueries {

    private ConservationQueries() {}

    /** What became of one input atom within one branch. */
    public enum InputFate {
        /** Reached an output verbatim (itself or via a covering whole-aggregate emission). */
        EMITTED_VERBATIM,
        /** Reached an output through one or more {@link Event.Combine}s. */
        FLOWS_DERIVED,
        /** Flowed into a call — unproven in v1 (fail-closed). */
        VIA_CALL,
        /** Consulted by the branch's guard but its content reached no output. */
        CONSULTED_ONLY,
        /** No event touches it — the silent-loss candidate. */
        UNTOUCHED,
        /** Inside an untraceable region — honest ignorance, fails everything. */
        OPAQUE
    }

    public static InputFate fateOf(ConservationBranch branch, AttributePath atom) {
        Flow flow = Flow.of(branch);
        if (flow.opaqueAll || flow.opaqueTouched.stream().anyMatch(p -> p.covers(atom))) {
            return InputFate.OPAQUE;
        }
        if (flow.verbatimEmits.stream().anyMatch(p -> p.covers(atom))) {
            return InputFate.EMITTED_VERBATIM;
        }
        if (flow.derivedEmittedOperands.stream().anyMatch(p -> p.covers(atom))) {
            return InputFate.FLOWS_DERIVED;
        }
        if (flow.callArgPaths.stream().anyMatch(p -> p.covers(atom))) {
            return InputFate.VIA_CALL;
        }
        if (flow.consulted.stream().anyMatch(p -> p.covers(atom))) {
            return InputFate.CONSULTED_ONLY;
        }
        return InputFate.UNTOUCHED;
    }

    /**
     * Every input atom reaches an output (verbatim or derived) in EVERY
     * branch. Fails closed on opaque regions and call-mediated flow.
     */
    public static boolean lossless(ConservationNode node) {
        return everyBranch(node, branch -> node.inputs().stream().allMatch(atom -> {
            InputFate fate = fateOf(branch, atom);
            return fate == InputFate.EMITTED_VERBATIM || fate == InputFate.FLOWS_DERIVED;
        }));
    }

    /**
     * The reversibility witness: in every branch, dataflow is a fan-in-free,
     * fan-out-free placement of inputs into outputs — no combination, no
     * calls, no opaque regions; every input emitted verbatim exactly once;
     * every output single-sourced. A bijective rewiring is structurally
     * invertible.
     */
    public static boolean verbatimBijection(ConservationNode node) {
        return everyBranch(node, branch -> {
            for (Event e : branch.events()) {
                if (e instanceof Event.Combine || e instanceof Event.Call
                        || e instanceof Event.Opaque) {
                    return false;
                }
            }
            Flow flow = Flow.of(branch);
            for (AttributePath atom : node.inputs()) {
                if (flow.verbatimEmits.stream().filter(p -> p.covers(atom)).count() != 1) {
                    return false;
                }
            }
            for (AttributePath out : node.outputs()) {
                if (flow.emitTargets.stream().filter(t -> t.covers(out)).count() != 1) {
                    return false;
                }
            }
            return true;
        });
    }

    /** Some input atom's content is emitted more than once in some branch. */
    public static boolean duplicated(ConservationNode node) {
        return !noBranch(node, branch -> {
            Flow flow = Flow.of(branch);
            return node.inputs().stream().anyMatch(atom ->
                    flow.verbatimEmits.stream().filter(p -> p.covers(atom)).count() > 1);
        });
    }

    /** The input atoms no event touches in {@code branch} — the silent-loss list. */
    public static List<AttributePath> untouched(ConservationNode node, ConservationBranch branch) {
        return node.inputs().stream()
                .filter(atom -> fateOf(branch, atom) == InputFate.UNTOUCHED)
                .toList();
    }

    // --- branch quantifiers ("no branch does X", "every branch does Y") ---

    public static boolean everyBranch(ConservationNode node, Predicate<ConservationBranch> test) {
        return node.branches().stream().allMatch(test);
    }

    public static boolean noBranch(ConservationNode node, Predicate<ConservationBranch> test) {
        return node.branches().stream().noneMatch(test);
    }

    /**
     * Per-branch flow summary, derived from the event list. The derived-flow
     * closure chases {@link Event.Combine} chains: an operand path "reaches an
     * output" when some emitted derived id's transitive operand set covers it.
     */
    static final class Flow {
        final List<AttributePath> verbatimEmits = new ArrayList<>();
        final List<AttributePath> emitTargets = new ArrayList<>();
        final List<AttributePath> derivedEmittedOperands = new ArrayList<>();
        final List<AttributePath> callArgPaths = new ArrayList<>();
        final List<AttributePath> consulted = new ArrayList<>();
        final List<AttributePath> opaqueTouched = new ArrayList<>();
        boolean opaqueAll = false;

        static Flow of(ConservationBranch branch) {
            Flow flow = new Flow();
            // derived id -> directly contributing operand paths + derived deps.
            Map<String, List<AttributePath>> derivedPaths = new HashMap<>();
            Map<String, List<String>> derivedDeps = new HashMap<>();
            Set<String> emittedDerived = new HashSet<>();
            for (Event e : branch.events()) {
                switch (e) {
                    case Event.Consult c -> flow.consulted.addAll(c.subjects());
                    case Event.Combine c -> {
                        List<AttributePath> paths = new ArrayList<>();
                        List<String> deps = new ArrayList<>();
                        for (Provenance operand : c.operands()) {
                            if (operand instanceof Provenance.Path p) paths.add(p.path());
                            if (operand instanceof Provenance.Derived d) deps.add(d.id());
                            if (operand instanceof Provenance.Opaque o) flow.opaqueAll = true;
                        }
                        derivedPaths.put(c.id(), paths);
                        derivedDeps.put(c.id(), deps);
                    }
                    case Event.Emit em -> {
                        flow.emitTargets.add(em.target());
                        switch (em.source()) {
                            case Provenance.Path p -> flow.verbatimEmits.add(p.path());
                            case Provenance.Derived d -> emittedDerived.add(d.id());
                            case Provenance.Opaque o -> flow.opaqueAll = true;
                            default -> { /* Constant / CallResult: no input flow proven */ }
                        }
                    }
                    case Event.Call c -> {
                        for (Provenance arg : c.args()) {
                            if (arg instanceof Provenance.Path p) flow.callArgPaths.add(p.path());
                            if (arg instanceof Provenance.Derived d) {
                                // Paths feeding a call-bound derived chain flow into the call.
                                collectClosure(d.id(), derivedPaths, derivedDeps, flow.callArgPaths);
                            }
                        }
                    }
                    case Event.Opaque o -> {
                        if (o.touched().isEmpty()) {
                            flow.opaqueAll = true;  // can't even over-approximate
                        } else {
                            flow.opaqueTouched.addAll(o.touched());
                        }
                    }
                }
            }
            for (String id : emittedDerived) {
                collectClosure(id, derivedPaths, derivedDeps, flow.derivedEmittedOperands);
            }
            return flow;
        }

        private static void collectClosure(
                String id, Map<String, List<AttributePath>> derivedPaths,
                Map<String, List<String>> derivedDeps, List<AttributePath> out) {
            Set<String> seen = new HashSet<>();
            List<String> stack = new ArrayList<>(List.of(id));
            while (!stack.isEmpty()) {
                String current = stack.remove(stack.size() - 1);
                if (!seen.add(current)) continue;
                out.addAll(derivedPaths.getOrDefault(current, List.of()));
                stack.addAll(derivedDeps.getOrDefault(current, List.of()));
            }
        }
    }
}
