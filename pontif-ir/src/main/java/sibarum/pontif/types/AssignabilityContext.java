package sibarum.pontif.types;

/**
 * The environment {@link Assignability} resolves names against — for now just the {@link TypeCatalog}
 * (what each nominal name is: struct / native / trait / alias / primitive). Kept as its own type so the
 * engine can grow more inputs (e.g. a trait-impl view for trait satisfaction) without touching every
 * call site.
 */
public record AssignabilityContext(TypeCatalog catalog) {

    public static AssignabilityContext of(TypeCatalog catalog) {
        return new AssignabilityContext(catalog);
    }
}
