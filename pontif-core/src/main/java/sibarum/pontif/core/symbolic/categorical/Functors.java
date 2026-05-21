package sibarum.pontif.core.symbolic.categorical;

import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;

public final class Functors {

    private Functors() {}

    public static Functor identity() {
        return new Functor(
                "Identity",
                null,
                null,
                SymExpr.lam("X", SymExpr.var("X")),
                SymExpr.lam("f", SymExpr.var("f")));
    }

    public static Functor identity(Sort category) {
        return new Functor(
                "Identity[" + category.name() + "]",
                category,
                category,
                SymExpr.lam("X", SymExpr.var("X")),
                SymExpr.lam("f", SymExpr.var("f")));
    }

    public static Functor constant(String name, SymExpr fixedObject) {
        return new Functor(
                name,
                null,
                null,
                SymExpr.lam("X", fixedObject),
                SymExpr.lam("f", SymExpr.lam("k", SymExpr.var("k"))));
    }

    public static Functor compose(Functor outer, Functor inner) {
        if (outer.source() != null && inner.target() != null
                && !outer.source().equals(inner.target())) {
            throw new IllegalArgumentException(
                    "Cannot compose " + outer.name() + " ∘ " + inner.name()
                            + ": " + outer.name() + " expects source " + outer.source()
                            + " but " + inner.name() + " produces " + inner.target());
        }
        SymExpr composedObjectMap = SymExpr.lam("X",
                SymExpr.app(outer.objectMap(),
                        SymExpr.app(inner.objectMap(), SymExpr.var("X"))));
        SymExpr composedMorphismMap = SymExpr.lam("f",
                SymExpr.app(outer.morphismMap(),
                        SymExpr.app(inner.morphismMap(), SymExpr.var("f"))));
        return new Functor(
                outer.name() + "∘" + inner.name(),
                inner.source(),
                outer.target(),
                composedObjectMap,
                composedMorphismMap);
    }

    public static SymExpr composeMorphisms(SymExpr g, SymExpr f) {
        return SymExpr.lam("$x",
                SymExpr.app(g, SymExpr.app(f, SymExpr.var("$x"))));
    }

    public static SymExpr identityMorphism() {
        return SymExpr.lam("$x", SymExpr.var("$x"));
    }
}
