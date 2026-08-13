package sibarum.pontif.core.symbolic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
    // Struct-inheritance chain: each struct's is-a base (`struct Sub : Base` → Sub↦Base). A trait
    // impl assigned to Base (`assign trait Base:Trait`) covers every Sub:Base — the same way Assignability
    // widens a Sub value to Base — so #satisfies walks this chain, mirroring #isAncestorTrait for traits.
    // Consulted query-time, so it is independent of register/declare ordering.
    private final Map<String, String> structBase = new HashMap<>();

    /**
     * Bare-on-<em>both</em>-sides satisfaction: does some registered type whose bare name is {@code bareType}
     * directly satisfy some trait whose bare name is {@code bareTrait}? Unlike {@link #satisfiesBareTrait}
     * (which bares the trait but matches the type exactly, since its callers hold a fully-qualified emitted
     * type), this bares the type too — for callers holding two <b>bare</b> keys, e.g. the conductor-graph
     * single-owner check comparing two bare-keyed conduit event types for ancestry overlap.
     */
    public boolean satisfiesBareBoth(String bareTrait, String bareType) {
        if (bareTrait == null || bareType == null) return false;
        String wantTrait = bare(bareTrait);
        String wantType = bare(bareType);
        for (Map.Entry<String, Set<String>> e : satisfiers.entrySet()) {
            if (!bare(e.getKey()).equals(wantTrait)) continue;
            for (String type : e.getValue()) {
                if (bare(type).equals(wantType)) return true;
            }
        }
        return false;
    }

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
     * Records {@code structName}'s is-a base struct ({@code struct Sub : Base} ⟹
     * {@code declareStruct("Sub", "Base")}), so a trait impl assigned to {@code Base} is
     * inherited by {@code Sub} through {@link #satisfies} / {@link #structAncestry}. A
     * null/empty base records no inheritance. Idempotent.
     */
    public TraitRegistry declareStruct(String structName, String base) {
        if (structName == null || structName.isEmpty()) {
            throw new IllegalArgumentException("structName must be non-empty");
        }
        if (base != null && !base.isEmpty()) {
            structBase.put(structName, base);
        }
        return this;
    }

    /**
     * The struct-inheritance chain of {@code typeName}, nearest-first and INCLUDING
     * {@code typeName} itself ({@code Exp → BiOp}). The chain is linear (a struct has at most
     * one base), so "nearest ancestor that has an impl" is unambiguous — no diamond. Cycle-guarded
     * (an ill-founded base loop stops). Used by the runtime trait fallback to walk from the concrete
     * type up to the ancestor that actually declares the assigned method.
     */
    public List<String> structAncestry(String typeName) {
        List<String> chain = new ArrayList<>();
        if (typeName == null) return chain;
        Set<String> seen = new HashSet<>();
        String cur = typeName;
        while (cur != null && seen.add(cur)) {
            chain.add(cur);
            cur = structBaseOf(cur);
        }
        return chain;
    }

    /** {@code type}'s recorded base struct — exact key first, then bare-tolerant (the recorded key may
     *  be bare while the query is qualified, or vice-versa, across link stages). */
    private String structBaseOf(String type) {
        String direct = structBase.get(type);
        if (direct != null) return direct;
        String bareType = bare(type);
        for (Map.Entry<String, String> e : structBase.entrySet()) {
            if (bare(e.getKey()).equals(bareType)) return e.getValue();
        }
        return null;
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
        // Direct (self) satisfaction — exact, unchanged behavior.
        if (satisfiesDirectly(traitName, typeName)) return true;
        // Struct inheritance: an impl assigned to an ANCESTOR struct covers this type. The self
        // entry (index 0) was tried exactly above; ancestors are matched bare-tolerantly since the
        // recorded base name and the registered satisfier may be qualified at different link stages.
        List<String> ancestry = structAncestry(typeName);
        for (int i = 1; i < ancestry.size(); i++) {
            String ancestor = ancestry.get(i);
            if (satisfiesDirectly(traitName, ancestor) || satisfiesBareBoth(traitName, ancestor)) {
                return true;
            }
        }
        return false;
    }

    /** Self-only satisfaction: does {@code typeName} itself (no struct-base walk) satisfy
     *  {@code traitName}, directly or via a sub-trait's base chain? The original {@link #satisfies}
     *  body, kept exact so the direct case introduces no bare-matching drift. */
    private boolean satisfiesDirectly(String traitName, String typeName) {
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

    /**
     * Fully qualifier-tolerant satisfaction: does {@code typeName} satisfy
     * {@code traitName}, matching either name bare-or-qualified on either side?
     * The single home for the "try all bare/qualified combinations" policy that
     * callers (the interpreter's match-arm trait gate, dispatch trait-param
     * enforcement) otherwise open-code. Delegates to {@link #satisfiesBareTrait},
     * which already bares the trait and tries the type both ways — a qualified
     * {@code traitName} bares to the same key, so this covers all four combos.
     */
    public boolean satisfiesTolerant(String traitName, String typeName) {
        if (traitName == null || typeName == null) return false;
        return satisfies(traitName, typeName) || satisfiesBareTrait(traitName, typeName);
    }

    /**
     * Is nominal type {@code sub} a STRICT subtype of {@code base} — i.e. does
     * every {@code sub} value necessarily fit where {@code base} is expected —
     * through the declared relations? Covers a struct is-a its base struct
     * ({@code S3:S1}), a struct implementing a trait ({@code S1} impl {@code T1},
     * including impls inherited down the struct chain), and a sub-trait extending
     * its super-trait ({@code trait B:A}). Reflexive identity is excluded (that is
     * {@link Refinements}' job via ordinary implication). This is the single
     * source for the specificity tiebreak's "more specific nominal" question.
     */
    public boolean isNominalSubtype(String sub, String base) {
        if (sub == null || base == null || sub.equals(base)) return false;
        if (isDeclaredTrait(base)) {
            // base is a trait: sub is a struct/type that satisfies it, or a sub-trait of it.
            return satisfies(base, sub) || (isDeclaredTrait(sub) && isAncestorTrait(base, sub));
        }
        // base is a struct: sub is-a base through the struct inheritance chain (strict).
        return structAncestry(sub).contains(base);
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
