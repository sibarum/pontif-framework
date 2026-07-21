package sibarum.pontif.types;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;

/**
 * The environment {@link Assignability} resolves names against: the {@link TypeCatalog} (what each
 * nominal name is) plus a trait-impl view ({@code typeName → traits it satisfies}) for the is-a-a-trait
 * rule. Kept as its own type so the engine can grow more inputs without touching every call site.
 */
public record AssignabilityContext(TypeCatalog catalog, Map<String, Set<String>> traitImpls) {

    public AssignabilityContext {
        traitImpls = Map.copyOf(traitImpls);
    }

    /** Catalog only — no trait impls (a type satisfies no trait). */
    public static AssignabilityContext of(TypeCatalog catalog) {
        return new AssignabilityContext(catalog, Map.of());
    }

    /** Catalog plus the {@code typeName → traits it satisfies} view. */
    public static AssignabilityContext of(TypeCatalog catalog, Map<String, Set<String>> traitImpls) {
        return new AssignabilityContext(catalog, traitImpls);
    }

    /**
     * Builds a context from a finished module — the single seam a call site uses to ask
     * {@link Assignability} about real code. The catalog comes from {@link TypeCatalog#fromModule};
     * the {@code typeName → traits} view is the <em>closed</em> satisfaction relation, reproducing
     * {@link sibarum.pontif.core.symbolic.TraitRegistry#satisfies}: each {@code assign trait T:Tr}
     * ({@link IrStmt.TraitImpl}) records {@code T} against {@code Tr} <em>and every ancestor of
     * {@code Tr}</em> up the {@code trait B : A} base chain ({@link IrSort.Trait#baseTrait()}), so a
     * type implementing a sub-trait is-a its base trait. The closure is precomputed here (rather than
     * walked per query as the registry does) because the engine only reads a flat membership set.
     */
    public static AssignabilityContext fromModule(IrModule module) {
        return new AssignabilityContext(TypeCatalog.fromModule(module), traitImplsOf(module));
    }

    /**
     * The <em>closed</em> {@code typeName → traits it satisfies} view of a module (see
     * {@link #fromModule}) — extracted so the dispatch context ({@code InferenceContext}) can carry
     * the same relation the engine reads, without rebuilding the whole catalog just to get it.
     */
    public static Map<String, Set<String>> traitImplsOf(IrModule module) {
        // trait → its immediate base trait (already null-normalized in IrSort.Trait).
        Map<String, String> baseOf = new HashMap<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.TypeAlias ta && ta.sort() instanceof IrSort.Trait t
                    && t.baseTrait() != null) {
                baseOf.put(t.name(), t.baseTrait());
            }
        }
        Map<String, Set<String>> traitImpls = new HashMap<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.TraitImpl ti) {
                Set<String> traits = traitImpls.computeIfAbsent(ti.typeName(), k -> new HashSet<>());
                // Walk the base chain, guarding cycles, adding the trait and each ancestor.
                Set<String> seen = new HashSet<>();
                String cur = ti.traitName();
                while (cur != null && seen.add(cur)) {
                    traits.add(cur);
                    cur = baseOf.get(cur);
                }
            }
        }
        return traitImpls;
    }

    /** Whether {@code typeName} satisfies {@code traitName} (a directly-registered impl). */
    boolean satisfies(String typeName, String traitName) {
        return typeName != null && traitImpls.getOrDefault(typeName, Set.of()).contains(traitName);
    }
}
