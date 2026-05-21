package sibarum.pontif.core.symbolic;

import sibarum.pontif.core.symbolic.algebra.ProofResult;

import java.util.ArrayList;
import java.util.List;

public final class FunctionCheck {

    private FunctionCheck() {}

    /**
     * Compile a call into a per-parameter outcome record:
     * Passed parameters need no further checking;
     * Failed parameters block execution at compile time;
     * Residual parameters are deferred to runtime checks.
     *
     * <p>The supplied {@code simplifier} may carry a {@link Context} of hypotheses;
     * stronger contexts → more {@code StaticallyPassed} outcomes → fewer runtime checks.
     */
    public static CompiledCall compileCall(
            FunctionDecl decl,
            List<SymExpr> arguments,
            Simplifier simplifier) {
        if (decl.parameters().size() != arguments.size()) {
            throw new IllegalArgumentException(
                    "Arity mismatch calling " + decl.name() + ": expected "
                            + decl.parameters().size() + " argument(s), got " + arguments.size());
        }
        List<CompiledCall.ParameterOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            FunctionDecl.Param param = decl.parameters().get(i);
            SymExpr arg = arguments.get(i);
            ProofResult r = Refinements.satisfies(arg, param.sort(), simplifier);
            if (r instanceof ProofResult.Passed) {
                outcomes.add(new CompiledCall.ParameterOutcome.StaticallyPassed(i));
            } else if (r instanceof ProofResult.Failed f) {
                outcomes.add(new CompiledCall.ParameterOutcome.StaticallyFailed(i, f.witness()));
            } else {
                outcomes.add(new CompiledCall.ParameterOutcome.DeferredCheck(
                        i,
                        new CompiledCall.RuntimeCheck(i, param.name(), param.sort(), arg)));
            }
        }
        return new CompiledCall(decl, arguments, outcomes);
    }

    /**
     * Convenience: summarise a compiled call as a single ProofResult.
     * Passed iff all parameters are statically passed.
     * Failed iff any parameter is statically failed (witness from the first failure).
     * Residual iff there are deferred checks (no static failures).
     */
    public static ProofResult verifyCall(
            FunctionDecl decl,
            List<SymExpr> arguments,
            Simplifier simplifier) {
        if (decl.parameters().size() != arguments.size()) {
            return ProofResult.failed(
                    "Arity mismatch calling " + decl.name() + ": expected "
                            + decl.parameters().size() + " argument(s), got " + arguments.size());
        }
        CompiledCall call = compileCall(decl, arguments, simplifier);
        if (!call.staticFailures().isEmpty()) {
            CompiledCall.ParameterOutcome.StaticallyFailed first = call.staticFailures().get(0);
            return ProofResult.failed(
                    "Argument " + first.parameterIndex() + " ("
                            + decl.parameters().get(first.parameterIndex()).name() + ") of "
                            + decl.name() + ": " + first.witness());
        }
        if (!call.deferredChecks().isEmpty()) {
            return ProofResult.residual(call.deferredChecks().get(0).argumentExpression());
        }
        return ProofResult.passed();
    }

    /**
     * Verify a function definition: simplify the body and check it against the
     * return sort. Pre-condition hypotheses can be supplied via the simplifier's
     * context for rung-2 reasoning.
     */
    public static ProofResult verifyDefinition(FunctionDecl decl, Simplifier simplifier) {
        if (!decl.hasBody()) {
            return ProofResult.passed();
        }
        Context ctx = simplifier.context();
        for (FunctionDecl.Param param : decl.parameters()) {
            if (param.sort().isRefined()) {
                SymExpr precondition = Substitute.applySelf(
                        param.sort().predicate(), SymExpr.var(param.name()));
                ctx = ctx.with(precondition);
            }
        }
        Simplifier enriched = simplifier.withContext(ctx);
        SymExpr simplifiedBody = enriched.simplify(decl.body());
        return Refinements.satisfies(simplifiedBody, decl.returnSort(), enriched);
    }
}
