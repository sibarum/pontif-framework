package sibarum.pontif.ir;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canonical collection of a module's declared struct definitions.
 *
 * <p>Structs lower to a preserved {@link IrStmt.TypeAlias} whose sort is an
 * {@link IrSort.Structural} (kept past {@link AliasResolver} so downstream
 * passes can resolve struct references by name rather than by inlining). Both
 * {@link SortChecker} and {@link InferenceContext} need the same name → struct
 * mapping; this is the single source so the two never drift.
 *
 * <p>With recursive types, a struct reference stays {@link IrSort.Named} and is
 * resolved <em>by name</em> against this registry on demand — never unrolled —
 * so the type graph can refer to itself through a constructor boundary.
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
        Map<String, IrSort.Structural> map = new LinkedHashMap<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.TypeAlias ta
                    && ta.sort() instanceof IrSort.Structural s) {
                map.put(ta.name(), s);
                map.putIfAbsent(s.name(), s);
            }
        }
        return map;
    }
}
