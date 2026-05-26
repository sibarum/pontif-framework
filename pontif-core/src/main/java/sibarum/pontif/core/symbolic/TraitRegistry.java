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

    /**
     * Declares that {@code traitName} is a known trait, even before any
     * concrete type satisfies it. Required for {@code function f(d:Trait)}
     * to type-check before any {@code assign trait T:Trait} block is seen.
     * Idempotent.
     */
    public TraitRegistry declareTrait(String traitName) {
        if (traitName == null || traitName.isEmpty()) {
            throw new IllegalArgumentException("traitName must be non-empty");
        }
        declaredTraits.add(traitName);
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
        return set != null && set.contains(typeName);
    }

    /** Read-only view of the satisfier set for a trait — empty if unregistered. */
    public Set<String> satisfiersOf(String traitName) {
        Set<String> set = satisfiers.get(traitName);
        return set == null ? Set.of() : Set.copyOf(set);
    }
}
