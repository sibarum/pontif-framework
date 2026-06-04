package sibarum.pontif.conservation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The conservation graph's node kinds — exactly the three the algebra ruled
 * ({@code docs/conservation-algebra.md}): <b>Computation</b> (operations and
 * resolved calls), <b>Branch</b> (matchers and dispatches — discrimination),
 * <b>Construction</b> (constructors and function returns). Everything else in
 * the IR is metadata on {@link Flow} edges. The sealed-ness here mirrors the
 * sealed-ness of {@code IrExpr}: a drafting switch with no default case is the
 * standing completeness proof of the taxonomy.
 */
public sealed interface FlowNode {

    String id();

    /** Operations and resolved calls. */
    record Computation(
            String id,
            String op,                 // "+", "<", "&", or "via <callee>" once composed
            OpClass opClass,
            Recoverability recoverability,
            List<Flow> inputs) implements FlowNode {
        public Computation { inputs = List.copyOf(inputs); }
    }

    /**
     * Discrimination: a measurement of the discriminants selects an arm.
     * Matchers today; dispatch sites compose in via summaries. A single-arm
     * Branch is an irrefutable destructure — it discriminates nothing.
     */
    record Branch(
            String id,
            List<Flow> discriminants,
            List<Arm> arms) implements FlowNode {
        public Branch {
            discriminants = List.copyOf(discriminants);
            arms = List.copyOf(arms);
        }
    }

    /** One arm: a display label (guard/pattern) and the arm's terminal flow. */
    record Arm(String label, Flow result) {}

    /**
     * Content placed into slots — record constructors AND function returns
     * (the result is constructed; a scalar return constructs the single
     * {@code r_0} slot). {@code claim} is the constructed type's name, the
     * tuple sentinel, or the return rendering for the return-construction.
     */
    record Construction(
            String id,
            String claim,
            Map<String, Flow> slots) implements FlowNode {
        public Construction {
            slots = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(slots));
        }
    }

    /** Conservation classes of the Op enum, per the algebra. */
    enum OpClass { ARITHMETIC, MEASUREMENT, LOGICAL }

    /**
     * What survives the operation, per operand (the conservative verdict over
     * the whole node; per-operand refinement is a later slice):
     * RECOVERABLE — operands reconstructible from result + co-operands
     * ({@code + -}); DEGRADED — content influenced the result but is not
     * reconstructible ({@code * / %} without the joint identity, logicals);
     * MEASUREMENT_BIT — one bit of relational information survives.
     */
    enum Recoverability { RECOVERABLE, DEGRADED, MEASUREMENT_BIT }

    static Optional<FlowNode> none() { return Optional.empty(); }
}
