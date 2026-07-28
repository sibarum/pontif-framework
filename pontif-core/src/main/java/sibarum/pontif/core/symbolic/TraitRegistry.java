package sibarum.pontif.core.symbolic;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Records which concrete struct types are assigned to which traits.
 * Consulted by {@link DispatchTable}'s trait-fallback rule: when a call
 * to {@code Trait.method} doesn't match any direct overload, the
 * dispatcher checks whether the first argument's concrete type is in
 * the trait's satisfier set and (if so) retries the lookup against
 * {@code ConcreteType.method}.
 *
 * <p>Built at compile time from {@code IrStmt.TraitImpl} declarations.
 * Empty by default — the trait fallback simply never fires until impls
 * are registered.
 */
public final class TraitRegistry {

    private final Map<String, Set<String>> satisfiers = new HashMap<>();
    private final Set<String> declaredTraits = new HashSet<>();
    // WAR(stream) trait-extends-trait: each trait's base (`trait B : A` → B↦A), for
    // transitive satisfaction — a T satisfying B is-a A, so dispatch on a bare A-typed
    // receiver resolves a T value. Consulted query-time (#satisfies), so it is
    // independent of register/declare ordering.
    private final Map<String, String> baseTrait = new HashMap<>();

    /**
     * Declares that {@code traitName} is a known trait, even before any
     * concrete type satisfies it. Required for {@code function f(d:Trait)}
     * to type-check before any {@code assign trait T:Trait} block is seen.
     * Idempotent.
     */
    public TraitRegistry declareTrait(String traitName) {
        return declareTrait(traitName, null);
    }

    /**
     * As {@link #declareTrait(String)} but also records {@code traitName}'s base
     * trait ({@code trait B : A} ⟹ {@code declareTrait("B", "A")}) for transitive
     * satisfaction. A null/empty base records no extension. Idempotent.
     */
    public TraitRegistry declareTrait(String traitName, String base) {
        if (traitName == null || traitName.isEmpty()) {
            throw new IllegalArgumentException("traitName must be non-empty");
        }
        declaredTraits.add(traitName);
        if (base != null && !base.isEmpty()) {
            baseTrait.put(traitName, base);
        }
        return this;
    }

    /** True iff {@code traitName} has been declared via {@link #declareTrait} or {@link #register}. */
    public boolean isDeclaredTrait(String traitName) {
        return traitName != null && declaredTraits.contains(traitName);
    }

    /**
     * Registers that {@code typeName} satisfies {@code traitName}.
     * Implicitly declares the trait too. Idempotent.
     */
    public TraitRegistry register(String traitName, String typeName) {
        if (traitName == null || traitName.isEmpty()) {
            throw new IllegalArgumentException("traitName must be non-empty");
        }
        if (typeName == null || typeName.isEmpty()) {
            throw new IllegalArgumentException("typeName must be non-empty");
        }
        declaredTraits.add(traitName);
        satisfiers.computeIfAbsent(traitName, k -> new HashSet<>()).add(typeName);
        return this;
    }

    /**
     * True iff {@code typeName} has been registered as a satisfier of
     * {@code traitName}. Both arguments must be non-null; either being
     * null yields false (no implicit "anything satisfies" rule).
     */
    public boolean satisfies(String traitName, String typeName) {
        if (traitName == null || typeName == null) return false;
        Set<String> set = satisfiers.get(traitName);
        if (set != null && set.contains(typeName)) return true;
        // Transitive (WAR(stream)): typeName may satisfy a SUB-trait whose base-chain
        // reaches traitName — a T implementing IndexedStream is-a Stream. Walk each
        // trait typeName directly satisfies up its base chain looking for traitName.
        for (Map.Entry<String, Set<String>> e : satisfiers.entrySet()) {
            if (e.getValue().contains(typeName) && isAncestorTrait(traitName, e.getKey())) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code ancestor} is a (strict) base trait of {@code trait}, transitively. */
    private boolean isAncestorTrait(String ancestor, String trait) {
        Set<String> seen = new HashSet<>();
        String cur = baseTrait.get(trait);
        while (cur != null && seen.add(cur)) {
            if (cur.equals(ancestor)) return true;
            cur = baseTrait.get(cur);
        }
        return false;
    }

    /**
     * Qualifier-tolerant {@link #satisfies}: the trait is matched by its BARE name (the suffix
     * after {@code '/'}), so a caller holding a bared key — e.g. an event-action bucket keyed by
     * the event type's base name — resolves against a fully-qualified registration
     * ({@code mod/GuiEvent}). The type is tried both as given and bare. Used by trait-aware event
     * routing (docs/reactive-gui.md §1), where bucket keys are bare but registrations are FQN.
     */
    public boolean satisfiesBareTrait(String bareTrait, String typeName) {
        if (bareTrait == null || typeName == null) return false;
        String wantTrait = bare(bareTrait);
        String bareType = bare(typeName);
        Set<String> traitNames = new HashSet<>(satisfiers.keySet());
        traitNames.addAll(declaredTraits);
        traitNames.addAll(baseTrait.keySet());
        traitNames.addAll(baseTrait.values());
        for (String t : traitNames) {
            if (bare(t).equals(wantTrait) && (satisfies(t, typeName) || satisfies(t, bareType))) {
                return true;
            }
        }
        return false;
    }

    private static String bare(String name) {
        if (name == null) return null;
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    /** Read-only view of the satisfier set for a trait — empty if unregistered. */
    public Set<String> satisfiersOf(String traitName) {
        Set<String> set = satisfiers.get(traitName);
        return set == null ? Set.of() : Set.copyOf(set);
    }
}
