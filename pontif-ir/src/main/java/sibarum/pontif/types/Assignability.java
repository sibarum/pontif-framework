package sibarum.pontif.types;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.ir.CallKinds;
import sibarum.pontif.ir.IrCompiler;
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
        return isA(sub, sup, ctx, new HashSet<>(), new HashSet<>());
    }

    /**
     * Cycle-guarded {@link #isA}. {@code subUnfolding}/{@code supUnfolding} hold the nominal names
     * currently being <em>widened</em> (a transparent-alias unfold or a nominal-tag → base widen) on
     * the active recursion path, one set per position. A well-founded (acyclic) catalog never repeats
     * a name on a single widening path, so these are empty for every real query; only an ill-founded
     * type ({@code type A : A}, {@code type A : A | Int}, a self-based struct) can revisit a name, and
     * that is where we stop — treating the ill-founded type as NOT is-a (sound: never a false is-a)
     * instead of recursing until the stack overflows. Names are added on entry and removed on exit
     * (backtracking), so a legitimate diamond ({@code Foo : Baz}, {@code Bar : Baz}) is unaffected.
     */
    private static boolean isA(IrSort sub, IrSort sup, AssignabilityContext ctx,
            Set<String> subUnfolding, Set<String> supUnfolding) {
        // Resolve transparent (non-structural) aliases in either position — they are pure names.
        IrSort tSub = transparentTarget(sub, ctx);
        if (tSub != null) {
            String n = baseName(sub);
            if (n != null && !subUnfolding.add(n)) return false;           // cyclic alias — ill-founded
            try {
                return isA(tSub, sup, ctx, subUnfolding, supUnfolding);
            } finally {
                if (n != null) subUnfolding.remove(n);
            }
        }
        IrSort tSup = transparentTarget(sup, ctx);
        if (tSup != null) {
            String n = baseName(sup);
            if (n != null && !supUnfolding.add(n)) return false;           // cyclic alias — ill-founded
            try {
                return isA(sub, tSup, ctx, subUnfolding, supUnfolding);
            } finally {
                if (n != null) supUnfolding.remove(n);
            }
        }

        if (sameType(sub, sup)) return true;                                // reflexive

        if (sup instanceof IrSort.Union u) {                               // is-a a union: any branch
            return u.branches().stream().anyMatch(b -> isA(sub, b, ctx, subUnfolding, supUnfolding));
        }
        if (sub instanceof IrSort.Union u) {                               // a union is-a X: every branch
            return u.branches().stream().allMatch(b -> isA(b, sup, ctx, subUnfolding, supUnfolding));
        }
        if (sup instanceof IrSort.Intersection i) {                        // is-a an intersection: EVERY branch
            return i.branches().stream().allMatch(b -> isA(sub, b, ctx, subUnfolding, supUnfolding));
        }
        if (sub instanceof IrSort.Intersection i) {                        // an intersection is-a X: SOME branch
            return i.branches().stream().anyMatch(b -> isA(b, sup, ctx, subUnfolding, supUnfolding));
        }
        if (sub instanceof IrSort.CallSig cSub && sup instanceof IrSort.CallSig cSup) {
            // Two call-signature sorts relate by their shared call-kind CAPABILITY,
            // never by a hardcoded Method/Dispatch instanceof (§2). A function-style
            // pair uses full function subtyping (contra params, covariant return); a
            // dispatch-style pair uses exact key-sort match + covariant return. The
            // two kinds never cross-assign.
            CallKinds.Kind ks = callKind(cSub.typeName(), ctx);
            CallKinds.Kind kt = callKind(cSup.typeName(), ctx);
            if (ks == CallKinds.Kind.FUNCTION && kt == CallKinds.Kind.FUNCTION) {
                return kernelImplies(sub, sup);
            }
            if (ks == CallKinds.Kind.DISPATCH && kt == CallKinds.Kind.DISPATCH) {
                return dispatchSubsumes(cSub, cSup, ctx, subUnfolding, supUnfolding);
            }
            return false;
        }

        // Parametric invariance (roadmap §4.2): two applications of the same head relate only through
        // their type-args, decided HERE — before the nominal-base widen below, which would drop the args
        // and name-match Box[Int] with Box[Bool]. Fires only for same-head plain-Named applications of
        // equal, non-empty arity; a bare-vs-applied pair (arity 0 vs n) falls through to the existing
        // name-only widen (a bare Box is the existential "Box of anything"). A type-variable arg is a
        // slot (its binding is the derivation machinery's job), so it matches — keeping Box[T] fields
        // usable by Box[Int] args at construction.
        if (sameHeadApplied(sub, sup)) {
            if (!typeArgsInvariant(typeArgsOf(sub), typeArgsOf(sup), ctx)) return false;
            // Args are invariantly compatible; a refined target adds a predicate obligation the
            // refinement-precise leg decides ([Box[Int]:@.v==5] is-a Box[Int], but not vice-versa).
            return !(sup instanceof IrSort.Refined) || refinementImplies(sub, sup);
        }

        // Two refinements over the SAME base: neither widens to the other, and for a
        // REGISTERED base (a struct/native) the nominal-tag guard below short-circuits to
        // false before structurallySubsumes could reach the kernel. Decide it directly —
        // sub's predicate must imply sup's ([Point:@.x>0] is-a [Point:@.x>=0]). Abstains to
        // false on predicates the kernel can't prove (sound — never a false is-a); reflexive
        // identical predicates are already handled by the predicate-aware sameType above.
        if (sub instanceof IrSort.Refined && sup instanceof IrSort.Refined
                && baseName(sub) != null && baseName(sub).equals(baseName(sup))) {
            return refinementImplies(sub, sup);
        }

        // A refinement widens to its own bare base — drop the predicate: [Decimal:@==0] is-a Decimal,
        // [Int:@>0] is-a Int, [Point:@.x>0] is-a Point. structurallySubsumes agrees, but the
        // isNominalTag/trait guard below would short-circuit a registered base (a Native like Decimal,
        // a struct) to false before reaching it. (Parametric same-head pairs are already handled above.)
        if (sub instanceof IrSort.Refined && !(sup instanceof IrSort.Refined)
                && baseName(sub) != null && baseName(sub).equals(baseName(sup))) {
            return true;
        }

        // is-a a trait: sub's type directly satisfies it (inherited impls ride the nominal-base widen below).
        boolean supIsTrait = isTrait(sup, ctx);
        if (supIsTrait && ctx.satisfies(baseName(sub), baseName(sup))) return true;

        IrSort subBase = nominalBase(sub, ctx);                            // a nominal tag widens to its base
        if (subBase != null) {
            String n = baseName(sub);
            if (n != null && !subUnfolding.add(n)) return false;           // cyclic nominal base — ill-founded
            try {
                if (isA(subBase, sup, ctx, subUnfolding, supUnfolding)) return true;
            } finally {
                if (n != null) subUnfolding.remove(n);
            }
        }

        // A nominal-tag or trait sup is reached only reflexively / by a descendant / by an impl (all
        // handled above); a bare structure or primitive is NOT-a either.
        if (isNominalTag(sup, ctx) || supIsTrait) return false;

        return structurallySubsumes(sub, sup, ctx, subUnfolding, supUnfolding);
    }

    /** What binding a value of concrete type {@code from} into a slot declared {@code to} requires. */
    public static Assignment assign(IrSort from, IrSort to, AssignabilityContext ctx) {
        if (isA(from, to, ctx)) return sameType(from, to) ? Assignment.EXACT : Assignment.WIDEN;
        // The numeric tower's lossless auto-conversion (Int -> Decimal) — a convenience/compatibility
        // coercion for primitives only. NOT an is-a, and it never applies to structs (roadmap §6.4).
        if (isNumericWidening(from, to)) return Assignment.COERCE;
        // A same-head parametric pair that isn't is-a has incompatible invariant type-args — no cast
        // retags one instantiation as another, so it is ILLEGAL, not NEEDS_CAST. (bottomStructure below
        // drops the args and would otherwise see Box[Int] and Box[Bool] as one castable shape.)
        if (sameHeadApplied(from, to)) return Assignment.ILLEGAL;
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

    private static boolean structurallySubsumes(IrSort sub, IrSort sup, AssignabilityContext ctx,
            Set<String> subUnfolding, Set<String> supUnfolding) {
        if (sub instanceof IrSort.Structural a && sup instanceof IrSort.Structural b) {
            if (!a.members().keySet().equals(b.members().keySet())) return false;
            for (Map.Entry<String, IrSort> e : a.members().entrySet()) {
                if (!isA(e.getValue(), b.members().get(e.getKey()), ctx, subUnfolding, supUnfolding)) return false;
            }
            return true;
        }
        String subName = baseName(sub);
        String supName = baseName(sup);
        if (subName == null || !subName.equals(supName)) return false;
        if (!(sup instanceof IrSort.Refined)) return true;   // widen to the bare base — drops any refinement
        // Refinement-precise leaf subsumption (roadmap §4.2): sub's predicate must IMPLY sup's — e.g.
        // [Int:@>0] is-a [Int:@>=0], and reflexively [Int:@>0] is-a itself, but NOT [Int:@>=0] is-a
        // [Int:@>0]. Delegated to the refinement kernel (Refinements.imply); the engine does not
        // re-implement predicate reasoning. (Note: sameType is predicate-blind — deliberately not used
        // here.) Abstains (false — sound, never a false is-a) on any predicate the linear kernel can't
        // compile or prove.
        return refinementImplies(sub, sup);
    }

    /** Does {@code sub}'s refinement predicate imply {@code sup}'s? (A bare base can't prove one.) */
    private static boolean refinementImplies(IrSort sub, IrSort sup) {
        return sub instanceof IrSort.Refined && kernelImplies(sub, sup);
    }

    /** {@code sub ⊑ sup} via the refinement kernel — compile both, ask {@code Refinements.imply}. */
    private static boolean kernelImplies(IrSort sub, IrSort sup) {
        try {
            return Refinements.imply(IrCompiler.compileSort(sub), IrCompiler.compileSort(sup),
                    new Simplifier(List.of())).isPassed();
        } catch (Exception abstain) {
            return false;  // outside the linear kernel — abstain, never fabricate an is-a
        }
    }

    /**
     * Metareference-contract subsumption: {@code [Dispatch(K):R] ⊑ [Dispatch(K'):R']} iff the key
     * sorts match (a dispatch never cross-assigns on different keys — docs/metatypes.md) and the
     * return is covariant. {@code Refinements.imply} has no dispatch arm, so this is decided directly.
     */
    private static boolean dispatchSubsumes(
            IrSort.CallSig sub, IrSort.CallSig sup, AssignabilityContext ctx,
            Set<String> subUnfolding, Set<String> supUnfolding) {
        if (sub.paramSorts().size() != sup.paramSorts().size()) return false;
        for (int i = 0; i < sub.paramSorts().size(); i++) {
            if (!sameType(sub.paramSorts().get(i), sup.paramSorts().get(i))) return false;
        }
        return isA(sub.returnSort(), sup.returnSort(), ctx, subUnfolding, supUnfolding);
    }

    /**
     * The call-kind capability of a head type — {@code Method}/builtins from the seed,
     * a user type from the {@code function-style}/{@code dispatch-style} trait-impl view.
     * {@code null} when {@code typeName} carries no call-kind capability (not callable).
     */
    private static CallKinds.Kind callKind(String typeName, AssignabilityContext ctx) {
        CallKinds.Kind builtin = CallKinds.builtin(typeName);
        if (builtin != null) return builtin;
        if (ctx.satisfies(typeName, CallKinds.DISPATCH_STYLE)) return CallKinds.Kind.DISPATCH;
        if (ctx.satisfies(typeName, CallKinds.FUNCTION_STYLE)) return CallKinds.Kind.FUNCTION;
        return null;
    }

    /** Strip nominal tags (and transparent aliases) down to the underlying structure/primitive. */
    private static IrSort bottomStructure(IrSort t, AssignabilityContext ctx) {
        return bottomStructure(t, ctx, new HashSet<>());
    }

    /**
     * Cycle-guarded {@link #bottomStructure}. {@code seen} holds the nominal names already stripped on
     * this path; an ill-founded type ({@code type A : A}, a self-based struct) would otherwise strip
     * forever. On a revisit we stop and return the current sort — the best-defined terminal available —
     * rather than overflow the stack.
     */
    private static IrSort bottomStructure(IrSort t, AssignabilityContext ctx, Set<String> seen) {
        String n = baseName(t);
        if (n != null && !seen.add(n)) return t;                           // cyclic — ill-founded, stop
        IrSort transparent = transparentTarget(t, ctx);
        if (transparent != null) return bottomStructure(transparent, ctx, seen);
        IrSort base = nominalBase(t, ctx);
        return base != null ? bottomStructure(base, ctx, seen) : t;
    }

    // --- identity ------------------------------------------------------------

    private static boolean sameType(IrSort a, IrSort b) {
        if (a instanceof IrSort.Structural sa && b instanceof IrSort.Structural sb) {
            // Identity is name + field NAMES + field SORTS. Comparing only the name and key-set would
            // equate two shapes that share a name but differ in a field's sort — e.g. inference's
            // synthetic `_tuple`/`_record` shapes, or `P{x:Int}` vs `P{x:Bool}` — and isA's reflexive
            // shortcut would then return a false is-a (Int wrongly usable as Bool). Field sorts are
            // compared by sameType (Named members are name-based, so recursion terminates on the
            // recursive-struct case `Node(next:Node)`).
            if (!sa.name().equals(sb.name()) || !sa.members().keySet().equals(sb.members().keySet())) {
                return false;
            }
            for (Map.Entry<String, IrSort> e : sa.members().entrySet()) {
                if (!sameType(e.getValue(), sb.members().get(e.getKey()))) return false;
            }
            return true;
        }
        // A call-signature sort is never "same type" by head name alone — two function
        // sorts both have base "Method" yet differ in params/return. Defer to the
        // dedicated CallSig subtyping arm (which decides reflexivity precisely), exactly
        // as the old null-baseName Method/Dispatch sorts did.
        if (a instanceof IrSort.CallSig || b instanceof IrSort.CallSig) return false;
        String an = baseName(a);
        String bn = baseName(b);
        if (an == null || !an.equals(bn)) return false;
        // Invariant type-args: two applications of the same head are the SAME type only if their
        // type-args are identical — Box[Int] is not Box[Bool] (recurses via sameType).
        if (!typeArgsEqual(typeArgsOf(a), typeArgsOf(b))) return false;
        boolean aRef = a instanceof IrSort.Refined;
        boolean bRef = b instanceof IrSort.Refined;
        if (aRef != bRef) return false;
        // Two refined sorts are the SAME type only if their predicates match. sameType must NOT be
        // predicate-blind — otherwise it wrongly equates [Int:@>=0] with [Int:@>0] and isA's reflexive
        // shortcut returns a false is-a. A predicate difference (or merely different origins) falls
        // through to the imply-based subsumption path, which decides it soundly (reflexivity via
        // alpha-equivalence, precise cases via Refinements.imply).
        return !aRef
                || ((IrSort.Refined) a).predicate().equals(((IrSort.Refined) b).predicate());
    }

    private static String baseName(IrSort sort) {
        return sort == null ? null : sort.baseName();
    }

    // --- parametric type-arguments (invariant — the only variance the language has) ------------------

    /** The applied type-arguments of a sort ({@code Named}/{@code Refined} carry them), else empty. */
    private static List<IrSort> typeArgsOf(IrSort s) {
        return switch (s) {
            case IrSort.Named n -> n.typeArgs();
            case IrSort.Refined r -> r.typeArgs();
            default -> List.of();
        };
    }

    /** Positional identity of two type-arg lists (for {@link #sameType}): same arity, each pair the
     *  SAME type — strict, no type-variable leniency (identity, not usability). */
    private static boolean typeArgsEqual(List<IrSort> a, List<IrSort> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!sameType(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    /** Invariant compatibility for an is-a between same-head applications (for {@link #isA}): same
     *  arity, and each positional pair compatible — a type <em>variable</em> (an undeclared bare
     *  {@code Named} — a slot) matches anything, else the args must be the SAME type (invariance,
     *  matching {@code SortChecker.enforceParametricBase}). */
    private static boolean typeArgsInvariant(List<IrSort> a, List<IrSort> b, AssignabilityContext ctx) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            IrSort x = a.get(i);
            IrSort y = b.get(i);
            if (isTypeVar(x, ctx) || isTypeVar(y, ctx)) continue;
            if (!sameType(x, y)) return false;
        }
        return true;
    }

    /** A type variable: a bare {@code Named} the catalog knows as neither a declared type nor a
     *  primitive — a type-parameter slot, not a concrete type (its binding is the derivation
     *  machinery's job, not this engine's). */
    private static boolean isTypeVar(IrSort s, AssignabilityContext ctx) {
        return s instanceof IrSort.Named n && n.typeArgs().isEmpty()
                && !ctx.catalog().isDeclared(n.name()) && !ctx.catalog().isPrimitive(n.name());
    }

    /** Two same-head applied parametric sorts of equal, non-empty arity — the shape the invariance
     *  arm judges by type-args. Accepts {@code Named} and {@code Refined} (a refined parametric like
     *  {@code [Box[Int]:@.v==5]}, the narrowing of a parametric construction, carries type-args too);
     *  a non-parametric refined sort ({@code [Int:@>0]}, empty type-args) is not matched. */
    private static boolean sameHeadApplied(IrSort a, IrSort b) {
        if (!isAppliedNominal(a) || !isAppliedNominal(b)) return false;
        List<IrSort> aa = typeArgsOf(a);
        return baseName(a).equals(baseName(b))
                && !aa.isEmpty() && aa.size() == typeArgsOf(b).size();
    }

    private static boolean isAppliedNominal(IrSort s) {
        return s instanceof IrSort.Named || s instanceof IrSort.Refined;
    }
}
