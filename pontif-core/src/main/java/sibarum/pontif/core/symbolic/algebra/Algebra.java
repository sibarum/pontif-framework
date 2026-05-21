package sibarum.pontif.core.symbolic.algebra;

import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Algebra {

    private final String name;
    private final List<BasisElement> basis;
    private final String identityName;
    private final Simplifier simplifier;
    private final Map<String, BasisElement> byName;

    public Algebra(
            String name,
            List<BasisElement> basis,
            String identityName,
            Simplifier simplifier) {
        this.name = name;
        this.basis = List.copyOf(basis);
        this.identityName = identityName;
        this.simplifier = simplifier;

        Map<String, BasisElement> map = new LinkedHashMap<>();
        for (BasisElement b : this.basis) {
            if (map.put(b.name(), b) != null) {
                throw new IllegalArgumentException("Duplicate basis name: " + b.name());
            }
        }
        if (identityName != null && !map.containsKey(identityName)) {
            throw new IllegalArgumentException(
                    "Identity '" + identityName + "' not in basis " + map.keySet());
        }
        this.byName = Map.copyOf(map);
    }

    public String name() { return name; }
    public List<BasisElement> basis() { return basis; }
    public String identityName() { return identityName; }
    public Simplifier simplifier() { return simplifier; }

    public Multivector multiply(Multivector a, Multivector b) {
        Multivector result = Multivector.zero();
        for (Map.Entry<String, SymExpr> aEntry : a.coefficients().entrySet()) {
            for (Map.Entry<String, SymExpr> bEntry : b.coefficients().entrySet()) {
                Multivector basisProduct = productOfBases(aEntry.getKey(), bEntry.getKey());
                SymExpr coeffProduct = simplifier.simplify(
                        SymExpr.mul(aEntry.getValue(), bEntry.getValue()));
                result = result.add(basisProduct.scale(coeffProduct, simplifier), simplifier);
            }
        }
        return result;
    }

    private Multivector productOfBases(String iName, String jName) {
        BasisElement bi = byName.get(iName);
        BasisElement bj = byName.get(jName);
        if (bi == null) throw new IllegalArgumentException("Unknown basis: " + iName);
        if (bj == null) throw new IllegalArgumentException("Unknown basis: " + jName);
        SymExpr product = simplifier.simplify(SymExpr.mul(bi.expression(), bj.expression()));
        return Multivector.fromSymExpr(product, basis, identityName);
    }

    public ProofResult prove(Property property) {
        return switch (property) {
            case CLOSED -> proveClosed();
            case ASSOCIATIVE -> proveAssociative();
        };
    }

    private ProofResult proveClosed() {
        for (BasisElement bi : basis) {
            for (BasisElement bj : basis) {
                SymExpr product = simplifier.simplify(
                        SymExpr.mul(bi.expression(), bj.expression()));
                try {
                    Multivector.fromSymExpr(product, basis, identityName);
                } catch (ClosureViolation ex) {
                    return ProofResult.failed(
                            bi.name() + " · " + bj.name() + " = " + product
                                    + " — " + ex.getMessage());
                }
            }
        }
        return ProofResult.passed();
    }

    private ProofResult proveAssociative() {
        for (BasisElement bi : basis) {
            for (BasisElement bj : basis) {
                for (BasisElement bk : basis) {
                    SymExpr leftAssoc = simplifier.simplify(
                            SymExpr.mul(
                                    SymExpr.mul(bi.expression(), bj.expression()),
                                    bk.expression()));
                    SymExpr rightAssoc = simplifier.simplify(
                            SymExpr.mul(
                                    bi.expression(),
                                    SymExpr.mul(bj.expression(), bk.expression())));
                    if (!leftAssoc.equals(rightAssoc)) {
                        return ProofResult.failed(
                                "(" + bi.name() + " · " + bj.name() + ") · " + bk.name()
                                        + " = " + leftAssoc
                                        + " but " + bi.name() + " · (" + bj.name() + " · " + bk.name() + ")"
                                        + " = " + rightAssoc);
                    }
                }
            }
        }
        return ProofResult.passed();
    }

    public Map<String, Map<String, SymExpr>> derivedTable() {
        Map<String, Map<String, SymExpr>> table = new LinkedHashMap<>();
        for (BasisElement bi : basis) {
            Map<String, SymExpr> row = new LinkedHashMap<>();
            for (BasisElement bj : basis) {
                row.put(bj.name(), simplifier.simplify(
                        SymExpr.mul(bi.expression(), bj.expression())));
            }
            table.put(bi.name(), row);
        }
        return table;
    }
}
