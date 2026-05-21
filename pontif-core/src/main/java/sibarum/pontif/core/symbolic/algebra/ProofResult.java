package sibarum.pontif.core.symbolic.algebra;

import sibarum.pontif.core.symbolic.SymExpr;

public sealed interface ProofResult permits ProofResult.Passed, ProofResult.Failed, ProofResult.Residual {

    record Passed() implements ProofResult {}

    record Failed(String witness) implements ProofResult {}

    record Residual(SymExpr obligation) implements ProofResult {}

    static ProofResult passed() {
        return new Passed();
    }

    static ProofResult failed(String witness) {
        return new Failed(witness);
    }

    static ProofResult residual(SymExpr obligation) {
        return new Residual(obligation);
    }

    default boolean isPassed() {
        return this instanceof Passed;
    }

    default boolean isResidual() {
        return this instanceof Residual;
    }
}
