package sibarum.pontif.conservation;

import sibarum.pontif.conservation.ConservationGraph.TypedAtom;
import sibarum.pontif.conservation.ConservationRoles.PathRoles;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A function's conservation interface — the "compressed/summarized format"
 * the design doc promised. Per input atom: the MUST relation to the result
 * (holds on EVERY branch-path — the sound direction for substitution into a
 * caller's ledger), whether the atom is spent in branching on every path, and
 * whether it touches residual flow on any path. Callers substitute this at
 * call sites instead of re-expanding the callee (the no-duplicate-edges rule,
 * compositionally).
 */
public record ConservationSummary(
        String functionName,
        List<AttributePath> inputAtoms,
        Map<AttributePath, Relation> relations,
        Set<AttributePath> spentEverywhere,
        Set<AttributePath> residualTouched,
        boolean anyPathPoisoned) {

    public ConservationSummary {
        inputAtoms = List.copyOf(inputAtoms);
        relations = Map.copyOf(relations);
        spentEverywhere = Set.copyOf(spentEverywhere);
        residualTouched = Set.copyOf(residualTouched);
    }

    /** How an input atom's content relates to the result, on every path. */
    public enum Relation {
        /** Content (verbatim or recoverable/degraded arithmetic chain) reaches the result. */
        CONTENT,
        /** Only a measurement bit of it reaches the result. */
        BIT,
        /** Nothing of it reaches the result. */
        NONE
    }

    /**
     * The optimistic fixpoint seed — lattice top: every atom's content
     * reaches the result, every atom is spent in branching, nothing is
     * residual. The inductive hypothesis for a cycle member's own calls
     * (the self-referential case of no-duplicate-edges); Kleene iteration
     * only ever descends from here, so the converged summary never claims
     * more than the body proves under the assumption.
     */
    public static ConservationSummary seed(String functionName, List<AttributePath> atoms) {
        Map<AttributePath, Relation> relations = new LinkedHashMap<>();
        for (AttributePath atom : atoms) relations.put(atom, Relation.CONTENT);
        return new ConservationSummary(functionName, atoms, relations,
                new HashSet<>(atoms), Set.of(), false);
    }

    public static ConservationSummary of(ConservationGraph graph) {
        List<PathRoles> paths = ConservationRoles.of(graph);
        Map<AttributePath, Relation> relations = new LinkedHashMap<>();
        Set<AttributePath> spent = new HashSet<>();
        Set<AttributePath> residual = new HashSet<>();
        boolean poisoned = paths.stream().anyMatch(p -> p.poisoned);
        List<AttributePath> atoms = graph.inputs().stream().map(TypedAtom::path).toList();
        for (AttributePath atom : atoms) {
            boolean contentEverywhere = true;
            boolean anyFlowEverywhere = true;
            boolean spentEverywhere = true;
            boolean touchesResidual = false;
            for (PathRoles path : paths) {
                contentEverywhere &= ConservationRoles.hasContentFlow(path, atom);
                anyFlowEverywhere &= ConservationRoles.hasAnyFlow(path, atom);
                spentEverywhere &= ConservationRoles.spentInBranching(path, atom);
                touchesResidual |= ConservationRoles.feedsResidual(path, atom);
            }
            relations.put(atom, contentEverywhere ? Relation.CONTENT
                    : anyFlowEverywhere ? Relation.BIT : Relation.NONE);
            if (spentEverywhere) spent.add(atom);
            if (touchesResidual) residual.add(atom);
        }
        return new ConservationSummary(
                graph.functionName(), atoms, relations, spent, residual, poisoned);
    }
}
