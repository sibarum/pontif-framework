package sibarum.pontif.ir;

import java.util.Map;

/**
 * Legacy struct-collection entry point — now a thin bridge over
 * {@link sibarum.pontif.types.TypeCatalog}, which holds the single interpretation of a module's
 * declared types. Retained so the IR callers that still take a plain {@code name → struct} map keep
 * working while they are migrated to consult the catalog directly ({@link
 * sibarum.pontif.types.TypeCatalog#shapeOf}/{@code lookup}); it drops away with the last of them.
 *
 * <p>Structs lower to a preserved {@link IrStmt.TypeAlias} whose sort is an
 * {@link IrSort.Structural} (kept past {@link AliasResolver} so downstream passes resolve struct
 * references by name rather than by inlining); with recursive types a struct reference stays
 * {@link IrSort.Named} and is resolved by name against the catalog on demand, never unrolled.
 */
public final class TypeRegistry {

    private TypeRegistry() {}

    /**
     * Builds {@code name → definition} from the module's preserved struct
     * type-aliases, in source order. Anonymous inline structural sorts (no
     * declaring alias) are not included — only named declarations.
     *
     * <p>A struct is registered under <em>both</em> names a reference can use:
     * the alias name ({@code (deftype Point (struct P …))} → {@code Point}, the
     * name written in param/return/let positions) and the struct's own internal
     * name ({@code P}, carried by record values and structural match patterns).
     * In the alt syntax these coincide ({@code struct Point(…)}); the S-expr
     * {@code deftype} form lets them differ, and both must resolve now that
     * struct references stay nominal rather than being inlined.
     */
    public static Map<String, IrSort.Structural> collect(IrModule module) {
        // Delegates to the consolidated catalog so struct interpretation lives in ONE place
        // (sibarum.pontif.types.TypeCatalog). This static entry point is a temporary bridge for the
        // IR callers not yet migrated to consult the catalog directly; it drops away with them.
        return sibarum.pontif.types.TypeCatalog.fromModule(module).structShapes();
    }
}
