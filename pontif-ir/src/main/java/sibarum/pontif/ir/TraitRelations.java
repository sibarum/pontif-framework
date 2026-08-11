package sibarum.pontif.ir;

import sibarum.pontif.core.symbolic.TraitRegistry;

/**
 * The one place a {@link TraitRegistry} is populated from an {@link IrModule}.
 *
 * <p>Both the runtime dispatch table (built in {@link IrCompiler}) and the
 * compile-time associated-type bound check (in {@link SortChecker}) need the
 * same relation — which structs satisfy which traits, plus the trait-extends
 * ({@code trait B : A}) and struct-inheritance ({@code struct Sub : Base})
 * chains that make satisfaction transitive. Deriving it in two places is exactly
 * the fork that let the bound check miss inherited impls; this centralizes the
 * derivation so both consumers see the same transitive relation.
 */
final class TraitRelations {

    private TraitRelations() {}

    /** Populates {@code registry} with every trait/struct/impl relation declared in {@code module}. */
    static void populate(IrModule module, TraitRegistry registry) {
        for (IrStmt stmt : module.statements()) {
            switch (stmt) {
                case IrStmt.TraitImpl ti -> registry.register(ti.traitName(), ti.typeName());
                case IrStmt.TypeAlias ta -> {
                    if (ta.sort() instanceof IrSort.Trait t) {
                        registry.declareTrait(t.name(), t.baseTrait());
                    } else if (ta.sort() instanceof IrSort.Structural s && s.baseSort() != null) {
                        registry.declareStruct(s.name(), Coercions.baseName(s.baseSort()));
                    }
                }
                default -> { }
            }
        }
    }

    /** A fresh registry populated from {@code module}. */
    static TraitRegistry from(IrModule module) {
        TraitRegistry registry = new TraitRegistry();
        populate(module, registry);
        return registry;
    }
}
