package sibarum.pontif.types;

import sibarum.pontif.ir.IrSort;

/**
 * The coercion decision, lifted verbatim from the parser's let-binding block (AltParser {@code parseLet},
 * docs/language-inventory.md §4). Given the value's inferred sort {@code from}, the claimed sort {@code to},
 * and the registries in a {@link CoercionContext}, it returns which {@link Coercion} applies — the parser
 * no longer decides this itself, it asks. Behaviour-preserving: the base-name comparison, the demotion and
 * trait cases, the {@code Int→Decimal} embedding, the anonymous-aggregate promotion, and the tuple→Stream
 * element gate are the same tests the parser ran inline, only relocated behind the facade.
 */
final class CoercionResolver {

    private static final String TUPLE_SENTINEL = "_tuple";

    private CoercionResolver() {}

    static Coercion resolve(IrSort from, IrSort to, CoercionContext ctx) {
        String declaredBase = baseSortName(to);
        String inferredBase = baseSortName(from);

        // The lossless Int→Decimal embedding is not a mismatch — DecimalPromotion promotes the literal
        // at IR time and the construction gate judges the claim. Checked before the mismatch guard.
        if ("Decimal".equals(declaredBase) && "Int".equals(inferredBase)) {
            return new Coercion.IntToDecimal();
        }
        // An anonymous aggregate ("_record") against a declared name is the promotion sugar, not a
        // mismatch — AggregatePromotion stamps and validates it at IR time (it also sees imported
        // structs the parser can't).
        if ("_record".equals(inferredBase)) {
            return new Coercion.RecordPromotion();
        }
        // The base mismatch: both bases known, genuinely different, the declared base not an unresolved
        // alias (real base unknown until AliasResolver runs), and the value's floor not the unknown "_"
        // (can't prove a mismatch, so abstain).
        if (declaredBase != null && inferredBase != null
                && !"_".equals(inferredBase)
                && !ctx.aliasNames().contains(declaredBase)
                && !declaredBase.equals(inferredBase)) {
            // A declared DEMOTION: the value's struct carries a base sort that demotes to the claimed
            // base (`struct Point3D:[Point:…]`), so `let b:Point = a` is a valid projection — the
            // ConstructionGate runs the morphism at IR time. Recorded at the demoted (base) sort.
            if (demotesTo(inferredBase, declaredBase, ctx)) {
                return new Coercion.Demote();
            }
            // Trait coercion, implicit in BOTH directions: the trait's attributes are computed
            // projections (nothing fabricated upward, nothing lost downward). Satisfaction is enforced
            // by SortChecker (the impl) and dispatch (only satisfiers resolve).
            if (ctx.traitNames().contains(declaredBase) || ctx.traitNames().contains(inferredBase)) {
                return new Coercion.TraitCast();
            }
            // tuple → Stream[T]: the one-way autobox (docs/iteration.md §8.6) — a clean forget of the
            // tuple's arity/positional identity, gated by every element being convertible to T.
            if ("Stream".equals(declaredBase) && TUPLE_SENTINEL.equals(inferredBase)) {
                String elementError = streamElementError(to, from);
                return elementError != null
                        ? new Coercion.Mismatch(elementError)
                        : new Coercion.Autobox();
            }
            return Coercion.Mismatch.generic();
        }
        return new Coercion.None();
    }

    /** Whether {@code fromBase}'s declared struct carries a base sort whose base is {@code toBase}. */
    private static boolean demotesTo(String fromBase, String toBase, CoercionContext ctx) {
        IrSort.Structural from = ctx.structDefs().get(fromBase);
        if (from == null || from.baseSort() == null) return false;
        return toBase.equals(baseSortName(from.baseSort()));
    }

    /**
     * The figurative tuple→{@code Stream[T]} element gate (docs/iteration.md §8.6): every member of the
     * tuple must be convertible to {@code T}. Base-level for now (exact base, plus the lossless
     * Int→Decimal embedding); the multi-dispatch promotion path will subsume it. Returns the specific
     * error message, or {@code null} when every element converts.
     */
    private static String streamElementError(IrSort declaredStream, IrSort tupleSort) {
        IrSort elemType = declaredStream instanceof IrSort.Named sn && !sn.typeArgs().isEmpty()
                ? sn.typeArgs().get(0) : null;
        String tBase = elemType == null ? null : baseSortName(elemType);
        if (tBase == null || !(tupleSort instanceof IrSort.Structural st)) return null;
        int idx = 0;
        for (IrSort m : st.members().values()) {
            String mBase = baseSortName(m);
            boolean ok = tBase.equals(mBase) || ("Decimal".equals(tBase) && "Int".equals(mBase));
            if (!ok) {
                return "Cannot box this tuple as Stream[" + describeSort(elemType) + "]: element "
                        + idx + " is " + describeSort(m) + ", not " + describeSort(elemType);
            }
            idx++;
        }
        return null;
    }

    private static String baseSortName(IrSort sort) {
        return switch (sort) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> r.name();
            case IrSort.Structural s -> s.name();
            case IrSort.Method f -> null;
            case IrSort.Dispatch d -> "Dispatch";
            case IrSort.Trait t -> t.name();
            // Cross-base unions/intersections have no single base name.
            case IrSort.Union u -> null;
            case IrSort.Intersection i -> null;
        };
    }

    /** A compact, human-readable rendering of a sort for error messages. */
    static String describeSort(IrSort s) {
        return switch (s) {
            case IrSort.Named n -> {
                if (n.typeArgs().isEmpty()) yield n.name();
                StringBuilder sb = new StringBuilder(n.name()).append("[");
                for (int i = 0; i < n.typeArgs().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(describeSort(n.typeArgs().get(i)));
                }
                yield sb.append("]").toString();
            }
            case IrSort.Refined r -> r.name();  // base only; the predicate is elided for readability
            case IrSort.Structural st -> {
                if (!TUPLE_SENTINEL.equals(st.name())) yield st.name();
                StringBuilder sb = new StringBuilder("(");
                boolean first = true;
                for (IrSort m : st.members().values()) {
                    if (!first) sb.append(", ");
                    sb.append(describeSort(m));
                    first = false;
                }
                yield sb.append(")").toString();
            }
            case IrSort.Method m -> "Method(…)";
            case IrSort.Dispatch d -> "Dispatch(…)";
            case IrSort.Union u -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < u.branches().size(); i++) {
                    if (i > 0) sb.append(" | ");
                    sb.append(describeSort(u.branches().get(i)));
                }
                yield sb.toString();
            }
            case IrSort.Intersection i -> {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < i.branches().size(); j++) {
                    if (j > 0) sb.append(" & ");
                    sb.append(describeSort(i.branches().get(j)));
                }
                yield sb.toString();
            }
            case IrSort.Trait t -> t.name();
        };
    }
}
