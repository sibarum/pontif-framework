package sibarum.pontif.conservation;

import sibarum.pontif.core.symbolic.SymExpr;

import java.util.List;
import java.util.Optional;

/**
 * The conservation ledger: dataflow provenance per function, recorded as
 * ordered events per branch. The structural sibling of the receipt graph —
 * the receipt graph tracks what values <em>are</em> (algebraic obligations);
 * this ledger tracks where they <em>went</em> (conservation obligations).
 * Compile-time only: not a sort, not a narrowing, nothing in the runtime —
 * receipts are for auditors, sorts are for callers.
 */
public record ConservationLedger(List<ConservationNode> nodes) {

    public ConservationLedger {
        nodes = List.copyOf(nodes);
    }

    public Optional<ConservationNode> node(String functionName) {
        return nodes.stream().filter(n -> n.functionName().equals(functionName)).findFirst();
    }

    /**
     * One function's ledger entry. {@code inputs} and {@code outputs} are the
     * flattened attribute atoms (params recursed through declared struct
     * sorts and tuple slots; the result rooted at {@code r_0}).
     */
    public record ConservationNode(
            String functionName,
            List<NamedSort> params,
            String returnRendering,
            List<AttributePath> inputs,
            List<AttributePath> outputs,
            List<ConservationBranch> branches) {
        public ConservationNode {
            params = List.copyOf(params);
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
            branches = List.copyOf(branches);
        }
    }

    /** A renamed param and the rendering of its sort (display only). */
    public record NamedSort(String name, String sortRendering) {}

    /**
     * One branch's event ledger, in order (sequence is load-bearing).
     * {@code guard} mirrors the receipt-graph branch guard; {@code patternNote}
     * carries a non-predicate pattern label (e.g. a claim pattern) for display.
     */
    public record ConservationBranch(
            Optional<SymExpr> guard,
            Optional<String> patternNote,
            List<Event> events) {
        public ConservationBranch {
            events = List.copyOf(events);
        }
    }
}
