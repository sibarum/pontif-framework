package sibarum.pontif.types;

import java.util.Map;
import java.util.Set;

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

    /** Whether {@code typeName} satisfies {@code traitName} (a directly-registered impl). */
    boolean satisfies(String typeName, String traitName) {
        return typeName != null && traitImpls.getOrDefault(typeName, Set.of()).contains(traitName);
    }
}
