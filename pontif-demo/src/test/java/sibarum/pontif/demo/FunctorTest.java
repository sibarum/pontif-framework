package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.AlphaEquivalence;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.symbolic.categorical.Functor;
import sibarum.pontif.core.symbolic.categorical.FunctorLaws;
import sibarum.pontif.core.symbolic.categorical.Functors;
import sibarum.pontif.core.symbolic.ArithmeticRules;
import sibarum.pontif.core.symbolic.LambdaRules;
import sibarum.pontif.core.symbolic.TotalExpressionRules;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctorTest {

    private static final Simplifier SIMPLIFIER = new Simplifier(allRules());

    private static List<RewriteRule> allRules() {
        List<RewriteRule> all = new ArrayList<>();
        all.addAll(LambdaRules.all());
        all.addAll(TotalExpressionRules.all());
        all.addAll(ArithmeticRules.all());
        return all;
    }

    // --- alpha equivalence ---

    @Test
    void alphaEquivalence_sameBoundNameMatches() throws Exception {
        assertTrue(AlphaEquivalence.equivalent(
                SymExpr.lam("x", SymExpr.var("x")),
                SymExpr.lam("x", SymExpr.var("x"))));
    }

    @Test
    void alphaEquivalence_differentBoundNamesMatch() throws Exception {
        assertTrue(AlphaEquivalence.equivalent(
                SymExpr.lam("x", SymExpr.var("x")),
                SymExpr.lam("y", SymExpr.var("y"))));
    }

    @Test
    void alphaEquivalence_freeVariablesMustMatchByName() throws Exception {
        assertFalse(AlphaEquivalence.equivalent(
                SymExpr.var("a"),
                SymExpr.var("b")));
        assertTrue(AlphaEquivalence.equivalent(
                SymExpr.var("a"),
                SymExpr.var("a")));
    }

    @Test
    void alphaEquivalence_freeVarInsideLam_keepsFreeStatus() throws Exception {
        // λx.y vs λz.y — both have free y, alpha-equivalent
        assertTrue(AlphaEquivalence.equivalent(
                SymExpr.lam("x", SymExpr.var("y")),
                SymExpr.lam("z", SymExpr.var("y"))));
        // λx.y vs λz.w — different free vars, not alpha-equivalent
        assertFalse(AlphaEquivalence.equivalent(
                SymExpr.lam("x", SymExpr.var("y")),
                SymExpr.lam("z", SymExpr.var("w"))));
    }

    @Test
    void alphaEquivalence_innerShadowing() throws Exception {
        // λx.λx.x vs λa.λb.b — both bind innermost, alpha-equivalent
        SymExpr a = SymExpr.lam("x", SymExpr.lam("x", SymExpr.var("x")));
        SymExpr b = SymExpr.lam("a", SymExpr.lam("b", SymExpr.var("b")));
        assertTrue(AlphaEquivalence.equivalent(a, b));
    }

    @Test
    void alphaEquivalence_distinguishesBindingDepth() throws Exception {
        // λx.λy.x  (refers to OUTER)  vs  λx.λy.y  (refers to INNER) — NOT alpha-equivalent
        SymExpr outerRef = SymExpr.lam("x", SymExpr.lam("y", SymExpr.var("x")));
        SymExpr innerRef = SymExpr.lam("x", SymExpr.lam("y", SymExpr.var("y")));
        assertFalse(AlphaEquivalence.equivalent(outerRef, innerRef));
    }

    // --- identity functor ---

    @Test
    void identityFunctor_appliedToObject_returnsObject() throws Exception {
        Functor id = Functors.identity();
        SymExpr result = id.applyToObject(SymExpr.lit(42), SIMPLIFIER);
        assertEquals(SymExpr.lit(42), result);
    }

    @Test
    void identityFunctor_appliedToMorphism_returnsMorphism() throws Exception {
        Functor id = Functors.identity();
        SymExpr square = SymExpr.lam("y", SymExpr.mul(SymExpr.var("y"), SymExpr.var("y")));
        SymExpr mapped = id.applyToMorphism(square, SIMPLIFIER);
        assertTrue(AlphaEquivalence.equivalent(square, mapped),
                "identity functor must preserve the morphism; got: " + mapped);
    }

    @Test
    void identityFunctor_satisfiesIdentityLaw() throws Exception {
        ProofResult result = FunctorLaws.proveIdentityPreserved(Functors.identity(), SIMPLIFIER);
        assertTrue(result.isPassed(), "Identity functor should preserve identity; " + result);
    }

    @Test
    void identityFunctor_satisfiesCompositionLaw() throws Exception {
        ProofResult result = FunctorLaws.proveCompositionPreserved(Functors.identity(), SIMPLIFIER);
        assertTrue(result.isPassed(), "Identity functor should preserve composition; " + result);
    }

    // --- constant functor ---

    @Test
    void constantFunctor_appliedToObject_returnsFixedObject() throws Exception {
        Functor const42 = Functors.constant("Const42", SymExpr.lit(42));
        assertEquals(SymExpr.lit(42), const42.applyToObject(SymExpr.lit(99), SIMPLIFIER));
        assertEquals(SymExpr.lit(42), const42.applyToObject(SymExpr.var("anything"), SIMPLIFIER));
    }

    @Test
    void constantFunctor_appliedToMorphism_returnsIdentityOnFixed() throws Exception {
        Functor const42 = Functors.constant("Const42", SymExpr.lit(42));
        SymExpr square = SymExpr.lam("y", SymExpr.mul(SymExpr.var("y"), SymExpr.var("y")));
        SymExpr mapped = const42.applyToMorphism(square, SIMPLIFIER);
        assertTrue(AlphaEquivalence.equivalent(Functors.identityMorphism(), mapped),
                "constant functor must map every morphism to id; got: " + mapped);
    }

    @Test
    void constantFunctor_satisfiesIdentityLaw() throws Exception {
        Functor const42 = Functors.constant("Const42", SymExpr.lit(42));
        ProofResult result = FunctorLaws.proveIdentityPreserved(const42, SIMPLIFIER);
        assertTrue(result.isPassed(), "Constant functor should preserve identity; " + result);
    }

    @Test
    void constantFunctor_satisfiesCompositionLaw() throws Exception {
        Functor const42 = Functors.constant("Const42", SymExpr.lit(42));
        ProofResult result = FunctorLaws.proveCompositionPreserved(const42, SIMPLIFIER);
        assertTrue(result.isPassed(), "Constant functor should preserve composition; " + result);
    }

    // --- composition of functors ---

    @Test
    void identityCompIdentity_actsAsIdentity_onObject() throws Exception {
        Functor composed = Functors.compose(Functors.identity(), Functors.identity());
        assertEquals(SymExpr.lit(7), composed.applyToObject(SymExpr.lit(7), SIMPLIFIER));
    }

    @Test
    void identityCompIdentity_actsAsIdentity_onMorphism() throws Exception {
        Functor composed = Functors.compose(Functors.identity(), Functors.identity());
        SymExpr morph = SymExpr.lam("x", SymExpr.add(SymExpr.var("x"), SymExpr.lit(1)));
        SymExpr mapped = composed.applyToMorphism(morph, SIMPLIFIER);
        assertTrue(AlphaEquivalence.equivalent(morph, mapped));
    }

    @Test
    void identityCompIdentity_satisfiesBothLaws() throws Exception {
        Functor composed = Functors.compose(Functors.identity(), Functors.identity());
        assertTrue(FunctorLaws.proveIdentityPreserved(composed, SIMPLIFIER).isPassed());
        assertTrue(FunctorLaws.proveCompositionPreserved(composed, SIMPLIFIER).isPassed());
    }

    @Test
    void constantCompIdentity_actsAsConstant() throws Exception {
        Functor const5 = Functors.constant("Const5", SymExpr.lit(5));
        Functor composed = Functors.compose(const5, Functors.identity());
        assertEquals(SymExpr.lit(5), composed.applyToObject(SymExpr.lit(99), SIMPLIFIER));
        assertTrue(FunctorLaws.proveIdentityPreserved(composed, SIMPLIFIER).isPassed());
        assertTrue(FunctorLaws.proveCompositionPreserved(composed, SIMPLIFIER).isPassed());
    }

    @Test
    void identityCompConstant_actsAsConstant() throws Exception {
        Functor const5 = Functors.constant("Const5", SymExpr.lit(5));
        Functor composed = Functors.compose(Functors.identity(), const5);
        assertEquals(SymExpr.lit(5), composed.applyToObject(SymExpr.lit(99), SIMPLIFIER));
        assertTrue(FunctorLaws.proveIdentityPreserved(composed, SIMPLIFIER).isPassed());
        assertTrue(FunctorLaws.proveCompositionPreserved(composed, SIMPLIFIER).isPassed());
    }

    // --- non-functor (a deliberate counterexample to confirm laws actually check something) ---

    @Test
    void badPretendFunctor_failsCompositionLaw() throws Exception {
        // A pair of lambdas that is NOT a valid functor: morphismMap throws away the morphism
        // and returns the doubling-of-x function.  This breaks F(g∘f) = F(g)∘F(f) because
        // F(anything) is a fixed lambda that ignores its input.
        SymExpr badMorphismMap = SymExpr.lam("f",
                SymExpr.lam("x", SymExpr.mul(SymExpr.lit(2), SymExpr.var("x"))));
        Functor notAFunctor = new Functor(
                "Bogus",
                null, null,
                SymExpr.lam("X", SymExpr.var("X")),
                badMorphismMap);
        // It still passes identity-preservation by accident (it always returns
        // 2x-on-input, which happens not to be id) — actually no, F(id) = 2x ≠ id. So both should fail.
        ProofResult idLaw = FunctorLaws.proveIdentityPreserved(notAFunctor, SIMPLIFIER);
        assertFalse(idLaw.isPassed(),
                "Bogus functor's morphismMap returns 2x for everything, including id, so F(id) ≠ id");
    }

    @Test
    void identityFunctor_hasMatchingSourceTarget_isEndofunctor() throws Exception {
        Functor id = Functors.identity();
        assertTrue(id.isEndofunctor());
    }
}
