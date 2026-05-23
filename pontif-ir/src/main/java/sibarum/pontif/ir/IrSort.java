package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;

import java.util.List;
import java.util.Map;

public sealed interface IrSort permits IrSort.Named, IrSort.Refined, IrSort.Structural, IrSort.Function {

    Origin origin();

    static Named named(String name) {
        return new Named(name, Origin.NONE);
    }

    static Refined refined(String name, IrExpr predicate) {
        return new Refined(name, predicate, Origin.NONE);
    }

    static Structural structural(String name, Map<String, IrSort> members) {
        return new Structural(name, members, Origin.NONE);
    }

    static Function function(List<IrSort> paramSorts, IrSort returnSort) {
        return new Function(paramSorts, returnSort, Origin.NONE);
    }

    record Named(String name, Origin origin) implements IrSort {}

    record Refined(String name, IrExpr predicate, Origin origin) implements IrSort {}

    record Structural(String name, Map<String, IrSort> members, Origin origin) implements IrSort {
        public Structural {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Structural sort name must be non-empty");
            }
            if (members == null) {
                throw new IllegalArgumentException("Structural sort members must be non-null");
            }
            // LinkedHashMap preserves field declaration order — critical for
            // destructure desugaring, which walks fields in declared order so
            // that `let x = p.x in let y = p.y in body` reads top-to-bottom in
            // the same order the user wrote the struct decl. Map.copyOf does
            // NOT preserve order.
            members = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(members));
        }
    }

    record Function(List<IrSort> paramSorts, IrSort returnSort, Origin origin) implements IrSort {
        public Function {
            if (paramSorts == null) {
                throw new IllegalArgumentException("Function paramSorts must be non-null");
            }
            if (returnSort == null) {
                throw new IllegalArgumentException("Function returnSort must be non-null");
            }
            paramSorts = List.copyOf(paramSorts);
        }
    }
}
