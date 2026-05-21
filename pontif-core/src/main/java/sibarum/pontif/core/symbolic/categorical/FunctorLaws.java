package sibarum.pontif.core.symbolic.categorical;

import sibarum.pontif.core.symbolic.AlphaEquivalence;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.ProofResult;

public final class FunctorLaws {

    private FunctorLaws() {}

    public static ProofResult proveIdentityPreserved(Functor functor, Simplifier simplifier) {
        SymExpr id = Functors.identityMorphism();
        SymExpr mapped = functor.applyToMorphism(id, simplifier);
        if (AlphaEquivalence.equivalent(id, mapped)) {
            return ProofResult.passed();
        }
        return ProofResult.failed(
                "F(id) ≠ id for functor " + functor.name() + ":  F(id) = " + mapped);
    }

    public static ProofResult proveCompositionPreserved(Functor functor, Simplifier simplifier) {
        SymExpr g = SymExpr.var("$g");
        SymExpr f = SymExpr.var("$f");
        SymExpr gAfterF = Functors.composeMorphisms(g, f);

        SymExpr leftSide = functor.applyToMorphism(gAfterF, simplifier);
        SymExpr mappedG = functor.applyToMorphism(g, simplifier);
        SymExpr mappedF = functor.applyToMorphism(f, simplifier);
        SymExpr rightSide = simplifier.simplify(Functors.composeMorphisms(mappedG, mappedF));

        if (AlphaEquivalence.equivalent(leftSide, rightSide)) {
            return ProofResult.passed();
        }
        return ProofResult.failed(
                "F(g∘f) ≠ F(g)∘F(f) for functor " + functor.name()
                        + ":  F(g∘f) = " + leftSide
                        + " but F(g)∘F(f) = " + rightSide);
    }
}
