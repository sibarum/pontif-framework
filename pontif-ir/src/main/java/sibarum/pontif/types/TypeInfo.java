package sibarum.pontif.types;

import sibarum.pontif.ir.IrSort;

/**
 * What a declared type name resolves to — the answer {@link TypeCatalog#lookup} returns. One nominal
 * name maps to exactly one of these: a user struct, a built-in (native) constructor, a trait, a
 * transparent alias, or a primitive. It is the type system's single vocabulary for "what kind of thing
 * is the name {@code X}", replacing the ad-hoc split registries (the parser's separate struct / trait /
 * alias maps, and the IR's struct-only {@code TypeRegistry}) each caller used to consult on its own.
 *
 * <ul>
 *   <li>{@link Struct} — a user-declared struct; {@code shape} carries its fields and (if it extends a
 *       base) its {@link IrSort.Structural#baseSort()} demotion target.</li>
 *   <li>{@link Native} — a built-in constructor (e.g. {@code Decimal}); same {@code shape} answer as a
 *       struct, kept distinct so callers that care can tell user types from built-ins.</li>
 *   <li>{@link Trait} — a declared trait, carrying its contract ({@link IrSort.Trait}).</li>
 *   <li>{@link Alias} — a transparent alias {@code type Name:[Sort]}; {@code target} is the aliased
 *       sort (unresolved until {@code AliasResolver} — the name's real base is not yet known).</li>
 *   <li>{@link Primitive} — a built-in scalar ({@code Int}/{@code Bool}/{@code Decimal}/{@code Char}/
 *       {@code String}) with no user declaration.</li>
 * </ul>
 */
public sealed interface TypeInfo {

    /** A user-declared struct. {@code shape.baseSort()} is its demotion base, or null if it has none. */
    record Struct(IrSort.Structural shape) implements TypeInfo {}

    /** A built-in constructor (e.g. {@code Decimal(unscaled, scale)}) — a shape, not user-declared. */
    record Native(IrSort.Structural shape) implements TypeInfo {}

    /** A declared trait and its contract. */
    record Trait(IrSort.Trait trait) implements TypeInfo {}

    /** A transparent alias; {@code target} is the aliased sort (may be unresolved pre-AliasResolver). */
    record Alias(IrSort target) implements TypeInfo {}

    /** A built-in scalar with no user declaration. */
    record Primitive(String name) implements TypeInfo {}
}
