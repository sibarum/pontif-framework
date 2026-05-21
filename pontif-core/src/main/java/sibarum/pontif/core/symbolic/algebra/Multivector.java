package sibarum.pontif.core.symbolic.algebra;

import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Multivector {

    private final Map<String, SymExpr> coefficients;

    public Multivector(Map<String, SymExpr> coefficients) {
        Map<String, SymExpr> copy = new LinkedHashMap<>();
        for (Map.Entry<String, SymExpr> e : coefficients.entrySet()) {
            if (!isZeroCoefficient(e.getValue())) {
                copy.put(e.getKey(), e.getValue());
            }
        }
        this.coefficients = Map.copyOf(copy);
    }

    public static Multivector zero() {
        return new Multivector(Map.of());
    }

    public static Multivector of(Map<String, SymExpr> coefficients) {
        return new Multivector(coefficients);
    }

    public Map<String, SymExpr> coefficients() {
        return coefficients;
    }

    public Multivector add(Multivector other, Simplifier simplifier) {
        Map<String, SymExpr> result = new LinkedHashMap<>(this.coefficients);
        for (Map.Entry<String, SymExpr> e : other.coefficients.entrySet()) {
            result.merge(e.getKey(), e.getValue(),
                    (existing, incoming) -> simplifier.simplify(SymExpr.add(existing, incoming)));
        }
        return new Multivector(result);
    }

    public Multivector scale(SymExpr scalar, Simplifier simplifier) {
        if (isZeroCoefficient(scalar)) {
            return Multivector.zero();
        }
        Map<String, SymExpr> result = new LinkedHashMap<>();
        for (Map.Entry<String, SymExpr> e : coefficients.entrySet()) {
            result.put(e.getKey(), simplifier.simplify(SymExpr.mul(scalar, e.getValue())));
        }
        return new Multivector(result);
    }

    public static Multivector fromSymExpr(
            SymExpr expr,
            List<BasisElement> basis,
            String identityName) {
        Map<String, SymExpr> coeffs = new LinkedHashMap<>();
        accumulate(coeffs, expr, basis, identityName);
        return new Multivector(coeffs);
    }

    private static void accumulate(
            Map<String, SymExpr> coeffs,
            SymExpr term,
            List<BasisElement> basis,
            String identityName) {
        if (term instanceof SymExpr.Add(SymExpr l, SymExpr r)) {
            accumulate(coeffs, l, basis, identityName);
            accumulate(coeffs, r, basis, identityName);
            return;
        }
        CoefficientBasis pair = matchCoefficientBasis(term, basis, identityName);
        coeffs.merge(pair.basisName(), pair.coefficient(),
                (existing, incoming) -> SymExpr.add(existing, incoming));
    }

    private record CoefficientBasis(SymExpr coefficient, String basisName) {}

    private static CoefficientBasis matchCoefficientBasis(
            SymExpr term,
            List<BasisElement> basis,
            String identityName) {
        if (isScalar(term)) {
            if (identityName == null) {
                throw new ClosureViolation(
                        "Cannot place scalar " + term + " — no identity basis declared");
            }
            return new CoefficientBasis(term, identityName);
        }
        for (BasisElement b : basis) {
            if (b.expression().equals(term)) {
                return new CoefficientBasis(SymExpr.lit(1), b.name());
            }
        }
        if (term instanceof SymExpr.Mul(SymExpr l, SymExpr r)) {
            if (isScalar(l)) {
                for (BasisElement b : basis) {
                    if (b.expression().equals(r)) {
                        return new CoefficientBasis(l, b.name());
                    }
                }
            }
            if (isScalar(r)) {
                for (BasisElement b : basis) {
                    if (b.expression().equals(l)) {
                        return new CoefficientBasis(r, b.name());
                    }
                }
            }
        }
        throw new ClosureViolation(
                "Cannot express " + term + " as a coefficient × basis-element");
    }

    private static boolean isScalar(SymExpr expr) {
        return expr instanceof SymExpr.Lit || expr instanceof SymExpr.Frac;
    }

    private static boolean isZeroCoefficient(SymExpr expr) {
        return (expr instanceof SymExpr.Lit l && l.value() == 0L)
                || (expr instanceof SymExpr.Frac f && f.num() == 0L);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Multivector other && coefficients.equals(other.coefficients);
    }

    @Override
    public int hashCode() {
        return coefficients.hashCode();
    }

    @Override
    public String toString() {
        if (coefficients.isEmpty()) return "0";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, SymExpr> e : coefficients.entrySet()) {
            if (!first) sb.append(" + ");
            sb.append(e.getValue()).append("·").append(e.getKey());
            first = false;
        }
        return sb.toString();
    }
}
