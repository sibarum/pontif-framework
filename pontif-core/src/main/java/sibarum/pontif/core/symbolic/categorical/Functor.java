package sibarum.pontif.core.symbolic.categorical;

import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;

public record Functor(
        String name,
        Sort source,
        Sort target,
        SymExpr objectMap,
        SymExpr morphismMap) {

    public Functor {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Functor name must be non-empty");
        }
        if (objectMap == null) {
            throw new IllegalArgumentException("objectMap must be non-null");
        }
        if (morphismMap == null) {
            throw new IllegalArgumentException("morphismMap must be non-null");
        }
    }

    public boolean isEndofunctor() {
        if (source == null && target == null) return true;
        return source != null && source.equals(target);
    }

    public SymExpr applyToObject(SymExpr object, Simplifier simplifier) {
        return simplifier.simplify(SymExpr.app(objectMap, object));
    }

    public SymExpr applyToMorphism(SymExpr morphism, Simplifier simplifier) {
        return simplifier.simplify(SymExpr.app(morphismMap, morphism));
    }
}
