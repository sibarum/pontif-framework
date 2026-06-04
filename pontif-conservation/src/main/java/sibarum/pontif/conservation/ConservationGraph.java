package sibarum.pontif.conservation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One function's conservation graph: typed input atoms, output atoms, the
 * node table, and the result flow (always a {@link FlowNode.Construction} or
 * a {@link FlowNode.Branch} whose arms terminate in constructions — returns
 * ARE construction, per the algebra).
 */
public record ConservationGraph(
        String functionName,
        String paramsRendering,
        String returnRendering,
        List<TypedAtom> inputs,
        List<AttributePath> outputs,
        Map<String, FlowNode> nodes,
        Flow result) {

    public ConservationGraph {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        nodes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
    }

    /**
     * An input atom with its content capacity, read from the DECLARED base
     * sort (never inferred narrowings — capacity must not change because
     * inference got smarter). The capacity law: measurement counts as
     * conservation exactly when it exhausts the measured content.
     */
    public record TypedAtom(AttributePath path, Capacity capacity) {}

    /** Content capacity by declared base sort. */
    public enum Capacity {
        /** Int / Decimal — measurement extracts one bit of many. */
        NUMERIC,
        /** Bool — one bit IS the content; branching on it spends all of it. */
        BIT,
        /** Unknown/other sorts — default to the numeric (conservative) rule. */
        OTHER
    }

    public FlowNode node(String id) {
        FlowNode n = nodes.get(id);
        if (n == null) throw new IllegalStateException("dangling node id " + id);
        return n;
    }

    /** The container for a drafted module. */
    public record Ledger(List<ConservationGraph> graphs) {
        public Ledger { graphs = List.copyOf(graphs); }

        public Optional<ConservationGraph> graph(String functionName) {
            return graphs.stream()
                    .filter(g -> g.functionName().equals(functionName)).findFirst();
        }
    }
}
