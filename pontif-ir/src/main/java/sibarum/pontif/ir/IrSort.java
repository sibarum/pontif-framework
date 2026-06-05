package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;

import java.util.List;
import java.util.Map;

public sealed interface IrSort permits IrSort.Named, IrSort.Refined, IrSort.Structural, IrSort.Method, IrSort.Dispatch, IrSort.Trait, IrSort.Union, IrSort.Intersection {

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

    static Method method(List<IrSort> paramSorts, IrSort returnSort) {
        return new Method(paramSorts, returnSort, Origin.NONE);
    }

    static Trait trait(String name, Map<String, IrSort.Method> methods) {
        return new Trait(name, methods, Origin.NONE);
    }

    static Union union(List<IrSort> branches) {
        return new Union(branches, Origin.NONE);
    }

    static Intersection intersection(List<IrSort> branches) {
        return new Intersection(branches, Origin.NONE);
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

    record Method(List<IrSort> paramSorts, IrSort returnSort, Origin origin) implements IrSort {
        public Method {
            if (paramSorts == null) {
                throw new IllegalArgumentException("Method paramSorts must be non-null");
            }
            if (returnSort == null) {
                throw new IllegalArgumentException("Method returnSort must be non-null");
            }
            paramSorts = List.copyOf(paramSorts);
        }
    }

    /**
     * Dispatch sort — the metareference's contract: a first-class DISPATCH
     * keyed at the given argument sorts ({@code [Dispatch(Int):Int]}). NOT a
     * method/closure: a dispatch value carries a name-keyed candidate set and
     * invocation reruns runtime dispatch, narrowings intact. The two sorts
     * mirror the two dispatch mechanisms and never cross-assign.
     */
    record Dispatch(List<IrSort> keySorts, IrSort returnSort, Origin origin) implements IrSort {
        public Dispatch {
            if (keySorts == null) {
                throw new IllegalArgumentException("Dispatch keySorts must be non-null");
            }
            if (returnSort == null) {
                throw new IllegalArgumentException("Dispatch returnSort must be non-null");
            }
            keySorts = List.copyOf(keySorts);
        }
    }

    /**
     * Trait sort: a named contract over method signatures. Values of this
     * sort are concrete struct types whose {@code Type.method}
     * declarations satisfy the contract — registered explicitly via
     * {@link IrStmt.TraitImpl} blocks. The contract method signatures
     * here exclude the implicit {@code self} parameter; SortChecker
     * prepends it at validation time.
     */
    record Trait(String name, Map<String, IrSort.Method> methods, Origin origin) implements IrSort {
        public Trait {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Trait name must be non-empty");
            }
            if (methods == null) {
                throw new IllegalArgumentException("Trait methods must be non-null");
            }
            methods = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(methods));
        }
    }

    /**
     * Union of cross-base sorts. Same-base unions (e.g.,
     * {@code [Int:0]|[Int:1]}) are normalized at parse time into a single
     * {@link Refined} sort with an {@code OR}-joined predicate; this variant
     * is reserved for unions whose branches don't share a common base
     * (e.g., {@code Int|Float}).
     *
     * <p>Branches are stored in source order. The dispatcher accepts a
     * value against a Union sort iff the value satisfies at least one
     * branch.
     */
    record Union(List<IrSort> branches, Origin origin) implements IrSort {
        public Union {
            if (branches == null) {
                throw new IllegalArgumentException("Union branches must be non-null");
            }
            branches = List.copyOf(branches);
            if (branches.size() < 2) {
                throw new IllegalArgumentException(
                        "Union must have at least two branches; got " + branches.size());
            }
        }
    }

    /**
     * Intersection of cross-base sorts. Same-base intersections (e.g.,
     * {@code [Int:@>0]&[Int:@<10]}) are normalized at parse time into a
     * single {@link Refined} sort with an {@code AND}-joined predicate;
     * this variant is reserved for the rare cross-base case.
     *
     * <p>The dispatcher accepts a value against an Intersection sort iff
     * the value satisfies every branch.
     */
    record Intersection(List<IrSort> branches, Origin origin) implements IrSort {
        public Intersection {
            if (branches == null) {
                throw new IllegalArgumentException("Intersection branches must be non-null");
            }
            branches = List.copyOf(branches);
            if (branches.size() < 2) {
                throw new IllegalArgumentException(
                        "Intersection must have at least two branches; got " + branches.size());
            }
        }
    }
}
