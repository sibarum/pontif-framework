package sibarum.pontif.core.symbolic;

import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.types.Sort;

import java.util.List;
import java.util.Map;

public record CompiledCall(
        FunctionDecl decl,
        List<SymExpr> arguments,
        List<ParameterOutcome> outcomes) {

    public CompiledCall {
        arguments = List.copyOf(arguments);
        outcomes = List.copyOf(outcomes);
    }

    public sealed interface ParameterOutcome
            permits ParameterOutcome.StaticallyPassed, ParameterOutcome.StaticallyFailed, ParameterOutcome.DeferredCheck {

        int parameterIndex();

        record StaticallyPassed(int parameterIndex) implements ParameterOutcome {}

        record StaticallyFailed(int parameterIndex, String witness) implements ParameterOutcome {}

        record DeferredCheck(int parameterIndex, RuntimeCheck check) implements ParameterOutcome {}
    }

    public record RuntimeCheck(
            int parameterIndex,
            String parameterName,
            Sort requiredSort,
            SymExpr argumentExpression) {

        public ProofResult evaluate(SymExpr concreteValue, Simplifier simplifier) {
            return Refinements.satisfies(concreteValue, requiredSort, simplifier);
        }
    }

    /** True iff every parameter was statically discharged at compile time. */
    public boolean isStaticallyComplete() {
        return outcomes.stream().allMatch(o -> o instanceof ParameterOutcome.StaticallyPassed);
    }

    /** False iff any parameter is statically known to violate its precondition. */
    public boolean canExecute() {
        return outcomes.stream().noneMatch(o -> o instanceof ParameterOutcome.StaticallyFailed);
    }

    public List<RuntimeCheck> deferredChecks() {
        return outcomes.stream()
                .filter(o -> o instanceof ParameterOutcome.DeferredCheck)
                .map(o -> ((ParameterOutcome.DeferredCheck) o).check())
                .toList();
    }

    public List<ParameterOutcome.StaticallyFailed> staticFailures() {
        return outcomes.stream()
                .filter(o -> o instanceof ParameterOutcome.StaticallyFailed)
                .map(o -> (ParameterOutcome.StaticallyFailed) o)
                .toList();
    }

    /**
     * Materialise the call: substitute the supplied bindings for any free variables
     * in deferred-check arguments, run the checks, and throw on any failure.
     * Passes silently if all checks pass or are themselves residual at this layer.
     */
    public void executeChecks(Map<String, SymExpr> bindings, Simplifier simplifier) {
        if (!canExecute()) {
            ParameterOutcome.StaticallyFailed first = staticFailures().get(0);
            throw new RuntimeCheckException(
                    "Cannot execute " + decl.name() + ": parameter "
                            + decl.parameters().get(first.parameterIndex()).name()
                            + " statically violates precondition — " + first.witness());
        }
        for (RuntimeCheck check : deferredChecks()) {
            SymExpr resolved = Substitute.apply(check.argumentExpression(), bindings);
            ProofResult r = check.evaluate(resolved, simplifier);
            if (r instanceof ProofResult.Failed f) {
                throw new RuntimeCheckException(
                        "Runtime check failed at " + decl.name()
                                + " parameter " + check.parameterName() + ": " + f.witness());
            }
        }
    }
}
