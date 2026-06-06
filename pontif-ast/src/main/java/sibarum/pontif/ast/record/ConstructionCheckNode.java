package sibarum.pontif.ast.record;

import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.types.Sort;

import java.util.List;

/**
 * A construction-claim check stamped by the compile-time gate: wraps one
 * constructor-argument node and judges its value against the declared field
 * sort at construction time. Exists only where the compile-time verdict was
 * undecidable — provable fits are never wrapped, provable misses never
 * compiled. Fail-closed: anything short of {@code Passed} rejects.
 */
public final class ConstructionCheckNode extends PontifNode {

    @Child private PontifNode value;
    private final Sort claim;
    private final String label;  // e.g. "Lift.base"
    private final Simplifier simplifier;

    private ConstructionCheckNode(PontifNode value, Sort claim, String label, Simplifier simplifier) {
        this.value = value;
        this.claim = claim;
        this.label = label;
        this.simplifier = simplifier;
    }

    public static ConstructionCheckNode of(
            PontifNode value, Sort claim, String label, Simplifier simplifier) {
        if (value == null || claim == null || simplifier == null) {
            throw new IllegalArgumentException(
                    "ConstructionCheckNode requires value, claim and simplifier");
        }
        return new ConstructionCheckNode(value, claim, label, simplifier);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object v = value.execute(frame);
        ProofResult result = Refinements.satisfies(RuntimeValues.toSymExpr(v), claim, simplifier);
        if (!(result instanceof ProofResult.Passed)) {
            throw new RuntimeCheckException(
                    "Construction claim violated: '" + label + "' = " + v
                            + " does not satisfy the declared sort " + claim,
                    origin());
        }
        return v;
    }

    @Override
    public List<PontifNode> children() {
        return List.of(value);
    }
}
