package sibarum.pontif.types;

import java.util.List;
import java.util.Map;

import sibarum.pontif.ir.IrSort;

/**
 * The is-a relation and the assignment rule — the target-state nominal-subtype engine
 * (docs/type-records.md), built isolated and wired to nothing yet (strangler-fig: prove it in a lab,
 * then migrate call sites onto it, then delete the scattered copies). Pure over {@link IrSort} +
 * {@link TypeCatalog}; no parser, no passes, no interpreter.
 *
 * <p><b>The model.</b> A value carries a concrete type; a binding is a view (its declared sort).
 * {@code isA(sub, sup)} answers "is a value whose concrete type is {@code sub} usable where {@code sup}
 * is required?" and {@link #assign} turns that into what a binding requires. The concrete type changes
 * only by construction or an explicit cast — never covertly — so a widen preserves it and anything else
 * needs a cast.
 *
 * <p><b>Nominal vs structural.</b> A {@code type X:[Def]} whose {@code Def} is a <em>structure</em>
 * (a tuple / named-field record) is a nominal <em>tag</em>: {@code X is-a Def} (widen — drop the tag),
 * but {@code Def is-not-a X} (the bare structure lacks the tag; you must construct/cast). Two tags over
 * the same structure ({@code Vec3}, {@code Color}) are siblings — neither is-a the other. A
 * {@code type X:[Def]} whose {@code Def} is a <em>union</em> (or other non-structural) is transparent —
 * a pure name for {@code Def} — so its branches widen into it as usual ({@code Int is-a AnyNumber}).
 *
 * <p><b>Scope (increment 1).</b> Structural leaf subsumption here is SHAPE equality (sound and
 * conservative — it never claims a false is-a); refinement-precise subsumption (delegating to
 * {@code Refinements.imply}, needed mainly for scalar bases) and trait satisfaction are later
 * increments. Construction and cast <em>decisions</em> likewise follow.
 */
public final class Assignability {

    private static final String TUPLE = "_tuple";

    private Assignability() {}

    /** Is a value whose concrete type is {@code sub} usable where {@code sup} is required? */
    public static boolean isA(IrSort sub, IrSort sup, AssignabilityContext ctx) {
        // Resolve transparent (non-structural) aliases in either position — they are pure names.
        IrSort tSub = transparentTarget(sub, ctx);
        if (tSub != null) return isA(tSub, sup, ctx);
        IrSort tSup = transparentTarget(sup, ctx);
        if (tSup != null) return isA(sub, tSup, ctx);

        if (sameType(sub, sup)) return true;                                // reflexive

        if (sup instanceof IrSort.Union u) {                               // is-a a union: any branch
            return u.branches().stream().anyMatch(b -> isA(sub, b, ctx));
        }
        if (sub instanceof IrSort.Union u) {                               // a union is-a X: every branch
            return u.branches().stream().allMatch(b -> isA(b, sup, ctx));
        }
        if (sup instanceof IrSort.Intersection i) {                        // is-a an intersection: EVERY branch
            return i.branches().stream().allMatch(b -> isA(sub, b, ctx));
        }
        if (sub instanceof IrSort.Intersection i) {                        // an intersection is-a X: SOME branch
            return i.branches().stream().anyMatch(b -> isA(b, sup, ctx));
        }

        // is-a a trait: sub's type directly satisfies it (inherited impls ride the nominal-base widen below).
        boolean supIsTrait = isTrait(sup, ctx);
        if (supIsTrait && ctx.satisfies(baseName(sub), baseName(sup))) return true;

        IrSort subBase = nominalBase(sub, ctx);                            // a nominal tag widens to its base
        if (subBase != null && isA(subBase, sup, ctx)) return true;

        // A nominal-tag or trait sup is reached only reflexively / by a descendant / by an impl (all
        // handled above); a bare structure or primitive is NOT-a either.
        if (isNominalTag(sup, ctx) || supIsTrait) return false;

        return structurallySubsumes(sub, sup, ctx);
    }

    /** What binding a value of concrete type {@code from} into a slot declared {@code to} requires. */
    public static Assignment assign(IrSort from, IrSort to, AssignabilityContext ctx) {
        if (isA(from, to, ctx)) return sameType(from, to) ? Assignment.EXACT : Assignment.WIDEN;
        // The numeric tower's lossless auto-conversion (Int -> Decimal) — a convenience/compatibility
        // coercion for primitives only. NOT an is-a, and it never applies to structs (roadmap §6.4).
        if (isNumericWidening(from, to)) return Assignment.COERCE;
        // Not is-a: legal only through an explicit cast, and only if the underlying structures are
        // compatible (a down/lateral/lossless retag). Otherwise there is no cast that could produce it.
        return sameType(bottomStructure(from, ctx), bottomStructure(to, ctx))
                ? Assignment.NEEDS_CAST : Assignment.ILLEGAL;
    }

    /** The closed numeric tower's one lossless auto-conversion: {@code Int → Decimal}. */
    private static boolean isNumericWidening(IrSort from, IrSort to) {
        return "Int".equals(baseName(from)) && "Decimal".equals(baseName(to));
    }

    /** The outcome of an assignment {@code let _:to = value(concrete=from)}. */
    public enum Assignment {
        /** Concrete type already equals the declared sort. */
        EXACT,
        /** A widen — {@code from is-a to}; the concrete type is preserved, no runtime work (a view). */
        WIDEN,
        /**
         * A lossless implicit CONVERSION — the numeric tower's {@code Int → Decimal} (and, later,
         * autobox). Unlike {@link #WIDEN} (a view, concrete preserved), it <em>changes</em> the
         * concrete value ({@code Long → BigDecimal}): convenience/numeric-compatibility sugar that
         * applies to primitives only, never structs. No explicit cast needed (roadmap §6.4).
         */
        COERCE,
        /** Not is-a, but a cast could produce it (compatible structure) — the caller must write one. */
        NEEDS_CAST,
        /** No cast could produce it — a hard type error. */
        ILLEGAL
    }

    // --- the two concrete-type-changers (decisions only; runtime execution lives in the interpreter) --

    /** The concrete type a construction/cast produces, or why it is rejected. */
    public sealed interface Made {
        /** The value's new concrete type. */
        record Ok(IrSort concreteType) implements Made {}
        /** No such value can be produced. */
        record Rejected(String reason) implements Made {}
    }

    /**
     * Can {@code X(args…)} construct — does {@code typeName} name a constructible nominal type whose
     * structure the argument sorts fit (positionally)? On success the produced value's concrete type is
     * {@code X} itself. This is how a bare literal <em>acquires</em> a nominal tag (`Vec3(1,2,3)`),
     * the only implicit-free way to move down from the structure to the tag.
     */
    public static Made construct(String typeName, List<IrSort> argSorts, AssignabilityContext ctx) {
        IrSort structure = nominalBase(IrSort.named(typeName), ctx);
        if (!(structure instanceof IrSort.Structural s)) {
            return new Made.Rejected("'" + typeName + "' is not a constructible nominal type");
        }
        if (s.members().size() != argSorts.size()) {
            return new Made.Rejected("'" + typeName + "' takes " + s.members().size()
                    + " field(s) but got " + argSorts.size());
        }
        int i = 0;
        for (IrSort member : s.members().values()) {
            if (!isA(argSorts.get(i), member, ctx)) {
                return new Made.Rejected("argument " + i + " is not usable as field of '" + typeName + "'");
            }
            i++;
        }
        return new Made.Ok(IrSort.named(typeName));
    }

    /**
     * Can {@code (target:value)} cast — is a value of concrete type {@code from} castable to
     * {@code target}? Legal whenever the underlying structures are compatible (a widen, a checked
     * narrow, or a lossless lateral re-tag between siblings); the value's new concrete type is
     * {@code target}. Only a structurally incompatible pair is rejected. Unlike a widen, a cast is the
     * <em>explicit</em> concrete-type change the programmer owns.
     */
    public static Made cast(IrSort target, IrSort from, AssignabilityContext ctx) {
        return assign(from, target, ctx) == Assignment.ILLEGAL
                ? new Made.Rejected("cannot cast " + baseName(from) + " to " + baseName(target)
                        + " — incompatible structures")
                : new Made.Ok(target);
    }

    // --- name resolution against the catalog ---------------------------------

    /** The target of a <em>transparent</em> alias (one whose definition is NOT a structure), else null. */
    private static IrSort transparentTarget(IrSort t, AssignabilityContext ctx) {
        String name = baseName(t);
        if (name == null) return null;
        return ctx.catalog().lookup(name).map(info ->
                info instanceof TypeInfo.Alias a && !(a.target() instanceof IrSort.Structural)
                        ? a.target() : null).orElse(null);
    }

    /**
     * The base a nominal <em>tag</em> widens to (its structure or its explicit struct-base), or null
     * when {@code t} is not a tag / is already a terminal structure. Two widen steps, in order:
     * <ol>
     *   <li>an explicit struct-base (`struct P3D:[Point:…]`) always wins — read from the <em>registered
     *       shape</em>, so a bare {@code Structural("P3D")} from inference demotes even if it doesn't
     *       carry the base field itself;</li>
     *   <li>otherwise a <em>named reference</em> widens to its structure, but a bare {@code Structural}
     *       that <em>is</em> that structure is terminal — returning its own shape would loop, since
     *       {@code fromModule} registers a struct under its shape's name.</li>
     * </ol>
     */
    private static IrSort nominalBase(IrSort t, AssignabilityContext ctx) {
        String name = baseName(t);
        if (name == null) return null;
        IrSort baseSort;
        IrSort shape;
        switch (ctx.catalog().lookup(name).orElse(null)) {
            case TypeInfo.Struct s -> { baseSort = s.shape().baseSort(); shape = s.shape(); }
            case TypeInfo.Alias a when a.target() instanceof IrSort.Structural -> {
                baseSort = null;
                shape = a.target();
            }
            // Not a registered nominal tag: a bare structure widens only to its own explicit base.
            case null, default -> { return t instanceof IrSort.Structural s ? s.baseSort() : null; }
        }
        if (baseSort != null) return baseSort;               // (1) explicit demote base
        return (t instanceof IrSort.Structural) ? null : shape;  // (2) named ref → structure; structure terminal
    }

    /** Whether {@code t} is a trait (an {@link IrSort.Trait} or a name the catalog knows as a trait). */
    private static boolean isTrait(IrSort t, AssignabilityContext ctx) {
        if (t instanceof IrSort.Trait) return true;
        String name = baseName(t);
        return name != null && ctx.catalog().lookup(name).orElse(null) instanceof TypeInfo.Trait;
    }

    /** Whether {@code t} names a nominal tag (a struct, native, or structural alias). */
    private static boolean isNominalTag(IrSort t, AssignabilityContext ctx) {
        String name = baseName(t);
        if (name == null) return false;
        return ctx.catalog().lookup(name).map(info -> switch (info) {
            case TypeInfo.Struct ignored -> true;
            case TypeInfo.Native ignored -> true;
            case TypeInfo.Alias a -> a.target() instanceof IrSort.Structural;
            default -> false;
        }).orElse(false);
    }

    // --- structural leaf (increment 1: shape equality — sound, refinement-precise later) -------------

    private static boolean structurallySubsumes(IrSort sub, IrSort sup, AssignabilityContext ctx) {
        if (sub instanceof IrSort.Structural a && sup instanceof IrSort.Structural b) {
            if (!a.members().keySet().equals(b.members().keySet())) return false;
            for (Map.Entry<String, IrSort> e : a.members().entrySet()) {
                if (!isA(e.getValue(), b.members().get(e.getKey()), ctx)) return false;
            }
            return true;
        }
        String subName = baseName(sub);
        String supName = baseName(sup);
        if (subName == null || !subName.equals(supName)) return false;
        // Widening to the same base is sound (drops any refinement); narrowing TO a refinement needs a
        // proof this increment doesn't attempt, so only an identical refined sup passes.
        return !(sup instanceof IrSort.Refined) || sameType(sub, sup);
    }

    /** Strip nominal tags (and transparent aliases) down to the underlying structure/primitive. */
    private static IrSort bottomStructure(IrSort t, AssignabilityContext ctx) {
        IrSort transparent = transparentTarget(t, ctx);
        if (transparent != null) return bottomStructure(transparent, ctx);
        IrSort base = nominalBase(t, ctx);
        return base != null ? bottomStructure(base, ctx) : t;
    }

    // --- identity ------------------------------------------------------------

    private static boolean sameType(IrSort a, IrSort b) {
        if (a instanceof IrSort.Structural sa && b instanceof IrSort.Structural sb) {
            return sa.name().equals(sb.name()) && sa.members().keySet().equals(sb.members().keySet());
        }
        String an = baseName(a);
        String bn = baseName(b);
        return an != null && an.equals(bn)
                && (a instanceof IrSort.Refined) == (b instanceof IrSort.Refined);
    }

    private static String baseName(IrSort sort) {
        if (sort == null) return null;
        return switch (sort) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> r.name();
            case IrSort.Structural s -> s.name();
            case IrSort.Trait t -> t.name();
            default -> null;
        };
    }
}
