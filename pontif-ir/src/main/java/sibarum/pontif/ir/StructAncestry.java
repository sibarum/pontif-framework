package sibarum.pontif.ir;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sibarum.pontif.core.QualifiedName;

/**
 * The one struct is-a base-chain walk over a {@code structDefs} map. A {@code
 * struct Sub:Base} edge is stored as the sub-struct's {@link IrSort.Structural#baseSort},
 * and several passes need to climb it — field-access resolution (a base field is
 * reachable on a sub value), construction (materialize a base's pinned fields),
 * and the call gate's ancestry view. They used to each open-code the same loop;
 * this centralizes it.
 *
 * <p>Resolution is canonical and bare-tolerant: each hop's base name is looked up
 * in {@code structDefs} exactly, then by its member (unqualified) name, so a walk
 * works whether the map is keyed bare (single-file) or FQN'd (post-link). The
 * start struct is re-resolved the same way — an inference-stripped instance (one
 * whose {@code baseSort} was dropped by narrowing) is replaced by its canonical
 * declaration. A seen-set guards against a cycle (declared structs form a forest,
 * but a malformed module must still terminate).
 *
 * <p>This is the {@code structDefs}-substrate walk. The registry-substrate
 * counterpart ({@link sibarum.pontif.core.symbolic.TraitRegistry#structAncestry},
 * over declared name relations) and the {@code InferenceContext} walk in
 * {@link sibarum.pontif.types.DispatchResolver} operate on different data and stay
 * separate.
 */
final class StructAncestry {

    private StructAncestry() {}

    /**
     * {@code [self, base, base-of-base, …]} nearest-first: the canonical form of
     * {@code start} followed by each is-a ancestor. Stops at a base that does not
     * resolve in {@code structDefs} (a native or external base) — the chain is
     * whatever declared structs were reachable.
     */
    static List<IrSort.Structural> selfAndAncestors(
            Map<String, IrSort.Structural> structDefs, IrSort.Structural start) {
        List<IrSort.Structural> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        IrSort.Structural cur = resolve(structDefs, start.name(), start);
        while (cur != null && seen.add(cur.name())) {
            chain.add(cur);
            if (cur.baseSort() == null) break;
            String base = Coercions.baseName(cur.baseSort());
            if (base == null) break;
            cur = resolve(structDefs, base, null);
        }
        return chain;
    }

    /** The proper is-a ancestors of {@code start}, nearest-first (self excluded). */
    static List<IrSort.Structural> ancestors(
            Map<String, IrSort.Structural> structDefs, IrSort.Structural start) {
        List<IrSort.Structural> chain = selfAndAncestors(structDefs, start);
        return chain.subList(1, chain.size());
    }

    private static IrSort.Structural resolve(
            Map<String, IrSort.Structural> structDefs, String name, IrSort.Structural fallback) {
        if (name == null) return fallback;
        IrSort.Structural s = structDefs.get(name);
        if (s == null) s = structDefs.get(QualifiedName.memberOf(name));
        return s != null ? s : fallback;
    }
}
