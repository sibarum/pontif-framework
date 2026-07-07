package sibarum.pontif.types;

/**
 * What {@link TypeSystem#coercionFor} consults to decide a coercion: the {@link TypeCatalog} — the one
 * registry of declared types — as it stands where the binding is made. Read-only from the resolver's
 * side. The catalog answers the three questions coercion asks:
 *
 * <ul>
 *   <li>a struct's shape and its {@code baseSort()} — what licenses a {@link Coercion.Demote} (does
 *       {@code from} demote to {@code to}?);</li>
 *   <li>whether a name is a trait — either side being one licenses a {@link Coercion.TraitCast}
 *       (implicit both directions);</li>
 *   <li>whether a name is a still-unresolved alias — which makes the type system <em>abstain</em>
 *       ({@link Coercion.None}) rather than guess a mismatch (its real base is unknown until
 *       {@code AliasResolver} runs).</li>
 * </ul>
 */
public record CoercionContext(TypeCatalog catalog) {
}
