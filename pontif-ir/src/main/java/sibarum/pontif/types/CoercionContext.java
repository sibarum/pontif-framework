package sibarum.pontif.types;

import java.util.Map;
import java.util.Set;

import sibarum.pontif.ir.IrSort;

/**
 * The registries {@link TypeSystem#coercionFor} consults to decide a coercion — the type-level facts a
 * caller has on hand at the point of a binding, handed in rather than re-derived. Read-only.
 *
 * <ul>
 *   <li>{@code structDefs} — declared structs by name; a struct's {@code baseSort()} is what licenses a
 *       {@link Coercion.Demote} (does {@code from} demote to {@code to}?).</li>
 *   <li>{@code traitNames} — names declared as traits; either side being a trait licenses a
 *       {@link Coercion.TraitCast} (implicit both directions).</li>
 *   <li>{@code aliasNames} — names declared as sort aliases, whose real base is unknown until
 *       {@code AliasResolver} runs; a declared alias makes the type system <em>abstain</em>
 *       ({@link Coercion.None}) rather than guess a mismatch.</li>
 * </ul>
 */
public record CoercionContext(
        Map<String, IrSort.Structural> structDefs,
        Set<String> traitNames,
        Set<String> aliasNames) {

    public CoercionContext {
        structDefs = Map.copyOf(structDefs);
        traitNames = Set.copyOf(traitNames);
        aliasNames = Set.copyOf(aliasNames);
    }
}
