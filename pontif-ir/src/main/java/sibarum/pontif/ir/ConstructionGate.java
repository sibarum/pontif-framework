package sibarum.pontif.ir;

import sibarum.pontif.types.Assignability;
import sibarum.pontif.types.Assignability.Assignment;
import sibarum.pontif.types.AssignabilityContext;
import sibarum.pontif.types.TypeSystem;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.Substitute;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.core.symbolic.BoundAnalysis;
import sibarum.pontif.predicates.ComplementResult;
import sibarum.pontif.predicates.PredicateArithmetic;
import sibarum.pontif.predicates.SatResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The claim rule's construction half, enforced: <b>construction is where
 * claims are made</b>, so a constructor argument is judged against its
 * declared field sort at the construction site, three ways:
 * <ul>
 *   <li><b>Provable fit</b> (the argument's narrowing implies the field
 *       sort) — passes with no runtime check; the proof discharged it.</li>
 *   <li><b>Provable miss</b> (the narrowing is disjoint from the field
 *       sort) — a compile error; the value would be born lying.</li>
 *   <li><b>Genuine overlap / undecidable</b> — compiles, stamped with a
 *       runtime check ({@link IrExpr.Record#runtimeChecks}) that the
 *       interpreter and Truffle lowering enforce at construction.</li>
 * </ul>
 *
 * <p>Deciding uses the same kernel as match totality:
 * {@link NarrowingInference} for the argument's sort,
 * {@link PredicateArithmetic} for implication/disjointness over the
 * decidable fragment, base-name comparison for cross-base verdicts
 * (an {@code [Int:…]} argument is disjoint from a struct-typed field),
 * and Union/Intersection recursion on the field side.
 *
 * <p>Deliberate leniencies, all in the substrate's existing direction:
 * a bare unregistered field sort (unrefined primitives) is not gated at
 * all (their legality is decided trait-free at the parser via
 * {@code Assignability} — roadmap §4.5 item 1); the Int→Decimal embedding
 * is never ruled disjoint; and a field sort the runtime cannot
 * satisfy-check (Method, Dispatch, inline structural) is never stamped.
 *
 * <p>Runs after {@link AggregatePromotion} (anonymous literals are
 * already stamped with their struct names) and {@link DecimalPromotion}
 * (Int literals at Decimal fields are already promoted), before
 * {@link SortChecker}.
 */
final class ConstructionGate {

    private ConstructionGate() {}

    static IrModule rewrite(IrModule module, Map<Origin.Span, IrSort> lens) throws CompileException {
        Map<String, IrSort.Structural> structs =
                sibarum.pontif.types.TypeCatalog.fromModule(module).structShapes();
        // The single nominal-subtype decider (roadmap §4.3): the base-name/nominal fit leg of
        // classify() delegates to Assignability, which knows widen/demotion/trait satisfaction the
        // old hand-rolled base compare did not. fromModule is cheap here (module in hand) and carries
        // the trait closure the parser lacks (roadmap §4.4).
        AssignabilityContext actx = AssignabilityContext.fromModule(module);
        InferenceContext base = InferenceContext.fromModule(module);
        List<IrStmt> out = new ArrayList<>(module.statements().size());
        for (IrStmt stmt : module.statements()) {
            out.add(switch (stmt) {
                case IrStmt.FunctionDecl fd -> rewriteFunction(fd, base, structs, actx, lens);
                case IrStmt.TraitImpl ti -> {
                    List<IrStmt.FunctionDecl> methods = new ArrayList<>(ti.methods().size());
                    for (IrStmt.FunctionDecl m : ti.methods()) {
                        methods.add(rewriteFunction(m, base, structs, actx, lens));
                    }
                    List<IrStmt.FunctionDecl> attrs = new ArrayList<>(ti.attributeProducers().size());
                    for (IrStmt.FunctionDecl a : ti.attributeProducers()) {
                        attrs.add(rewriteFunction(a, base, structs, actx, lens));
                    }
                    yield new IrStmt.TraitImpl(ti.typeName(), ti.traitName(), methods, attrs,
                            ti.typeBindings(), ti.typeParams(), ti.traitTypeArgs(), ti.origin());
                }
                default -> stmt;  // TypeAlias / Proof / Requires / Exports / NoOp
            });
        }
        IrExpr main = module.main() == null
                ? null
                : rewriteExpr(module.main(), base, structs, actx, lens);
        return new IrModule(module.name(), out, main);
    }

    private static IrStmt.FunctionDecl rewriteFunction(
            IrStmt.FunctionDecl fd, InferenceContext base, Map<String, IrSort.Structural> structs,
            AssignabilityContext actx, Map<Origin.Span, IrSort> lens) throws CompileException {
        InferenceContext ctx = base.withParams(fd.params());
        return new IrStmt.FunctionDecl(
                fd.name(), fd.params(), fd.returnSort(),
                rewriteExpr(fd.body(), ctx, structs, actx, lens), fd.origin(), fd.topLevelLet(),
                fd.typeParams());
    }

    /**
     * Structural rewrite threading the narrowing context the same way
     * {@link NarrowingInference} extends it: let bindings carry the value's
     * inferred narrowing (falling back to the declared sort), match arms
     * over a variable scrutinee carry the arm's pattern.
     */
    private static IrExpr rewriteExpr(
            IrExpr e, InferenceContext ctx, Map<String, IrSort.Structural> structs,
            AssignabilityContext actx, Map<Origin.Span, IrSort> lens) throws CompileException {
        return switch (e) {
            case IrExpr.Lit l -> l;
            case IrExpr.Dec d -> d;
            case IrExpr.Chr c -> c;
            case IrExpr.Str s -> s;
            case IrExpr.Bool b -> b;
            case IrExpr.Var v -> v;
            case IrExpr.SelfRef s -> s;
            case IrExpr.DispatchRef d -> d;
            case IrExpr.BinOp op -> new IrExpr.BinOp(
                    op.op(),
                    rewriteExpr(op.left(), ctx, structs, actx, lens),
                    rewriteExpr(op.right(), ctx, structs, actx, lens),
                    op.origin());
            case IrExpr.LetIn l -> {
                IrExpr value = rewriteExpr(l.value(), ctx, structs, actx, lens);
                // §6.5 (docs/type-system-roadmap.md): demotion is a VIEW, not a re-stamp. A
                // `let flat:Point = p3d` where the value's concrete type is-a the claim's base
                // is a proven widen — the concrete `Point3D` value is RETAINED (no projection,
                // no forget), viewed at the declared base sort; static access is restricted to
                // that view. Concrete identity changes only by construction / explicit cast.
                // (Formerly this projected a fresh base record — the re-stamp we retired.)
                if (l.claim() != null && demotesToClaim(value, l.claim(), ctx, structs)) {
                    IrSort declared = l.claim();
                    InferenceContext viewCtx = ctx.withVar(l.name(), declared);
                    yield new IrExpr.LetIn(l.name(), declared, value,
                            rewriteExpr(l.body(), viewCtx, structs, actx, lens), l.origin(), null);
                }
                IrSort inferred = TypeSystem.standard().infer(l.value(), ctx);
                IrSort bound = inferred != null ? inferred : l.declaredSort();
                InferenceContext bodyCtx = bound != null ? ctx.withVar(l.name(), bound) : ctx;
                yield new IrExpr.LetIn(l.name(), l.declaredSort(), value,
                        rewriteExpr(l.body(), bodyCtx, structs, actx, lens), l.origin(),
                        gateClaim(l, value, ctx, structs, actx, lens));
            }
            case IrExpr.Call c -> {
                List<IrExpr> args = new ArrayList<>(c.args().size());
                for (IrExpr a : c.args()) args.add(rewriteExpr(a, ctx, structs, actx, lens));
                yield new IrExpr.Call(c.functionName(), args, c.origin());
            }
            case IrExpr.Lambda lam -> {
                InferenceContext bodyCtx = ctx;
                for (IrParam p : lam.params()) {
                    if (p.sort() != null) bodyCtx = bodyCtx.withVar(p.name(), p.sort());
                }
                yield new IrExpr.Lambda(lam.params(), lam.returnSort(),
                        rewriteExpr(lam.body(), bodyCtx, structs, actx, lens), lam.origin());
            }
            case IrExpr.Apply app -> {
                List<IrExpr> args = new ArrayList<>(app.args().size());
                for (IrExpr a : app.args()) args.add(rewriteExpr(a, ctx, structs, actx, lens));
                yield new IrExpr.Apply(rewriteExpr(app.fn(), ctx, structs, actx, lens), args, app.origin());
            }
            case IrExpr.Match m -> {
                IrExpr scrutinee = rewriteExpr(m.scrutinee(), ctx, structs, actx, lens);
                List<IrExpr.MatchBranch> branches = new ArrayList<>(m.branches().size());
                for (IrExpr.MatchBranch b : m.branches()) {
                    InferenceContext armCtx =
                            m.scrutinee() instanceof IrExpr.Var v && b.pattern() != null
                                    ? ctx.withVar(v.name(), b.pattern())
                                    : ctx;
                    branches.add(new IrExpr.MatchBranch(
                            b.pattern(), rewriteExpr(b.result(), armCtx, structs, actx, lens)));
                }
                yield new IrExpr.Match(scrutinee, branches, m.origin());
            }
            case IrExpr.Record r -> gateRecord(r, ctx, structs, actx, lens);
            case IrExpr.FieldAccess fa -> new IrExpr.FieldAccess(
                    rewriteExpr(fa.base(), ctx, structs, actx, lens), fa.fieldName(), fa.origin());
            case IrExpr.MethodCall mc -> throw MethodResolver.unresolved(mc, "ConstructionGate");
            // REVISIT (docs/iteration.md §10): no construction-claim gating inside
            // the source / arm writes yet (slice 1 builds those explicitly).
            case IrExpr.Iterate it -> it;
            case IrExpr.Emit em -> new IrExpr.Emit(
                    rewriteExpr(em.event(), ctx, structs, actx, lens),
                    rewriteExpr(em.body(), ctx, structs, actx, lens), em.origin());
            case IrExpr.Cast cast -> new IrExpr.Cast(cast.targetSort(),
                    rewriteExpr(cast.value(), ctx, structs, actx, lens), cast.origin());
        };
    }

    /**
     * The claim rule's binding half: a declared sort at a let is judged
     * against the value's narrowing exactly like a constructor argument.
     * Returns null when discharged (FITS) or absent, throws on an unprovable
     * or disjoint claim (§1d), and returns the claim only for the sanctioned
     * parametric-{@code Stream} runtime element check.
     *
     * <p>Same leniencies as the record gate, deliberately: a bare unregistered
     * claim ({@code let x:Int = …}) is not gated here — its legality is decided
     * trait-free at the parser via {@code Assignability} (roadmap §4.5 item 1) —
     * and a claim the runtime cannot satisfy-check is never kept.
     */
    private static IrSort gateClaim(
            IrExpr.LetIn l, IrExpr value, InferenceContext ctx, Map<String, IrSort.Structural> structs,
            AssignabilityContext actx, Map<Origin.Span, IrSort> lens) throws CompileException {
        IrSort claim = l.claim();
        if (claim == null || !gated(claim, structs)) return null;
        // A parametric Stream[T] claim is kept for the runtime element check (§8.6):
        // the element type of a computed stream isn't statically decidable, so this is
        // always an UNKNOWN — skip classify (which judges base sorts, not elements).
        if (isParametricStream(claim)) return claim;
        IrSort arg = effectiveArg(value, lens, ctx, structs);
        // Dependent refinement: a claim predicate may name a preceding in-scope
        // binding (`let x = 5; let y:[Int:@>=x] = …`). Substitute each referenced
        // binding's pinned value into the predicate before deciding — the same move
        // the call gate makes for sibling params (StaticDispatch.substituteSiblings,
        // docs/dependent-sorts.md §5.2). A value the scope pins makes the claim
        // decidable (FITS discharges it; DISJOINT is a compile error); an unpinned
        // reference (a param, a computed local) stays free, and the ORIGINAL claim is
        // stamped so the runtime resolves it against the concrete scope.
        IrSort resolved = substituteScope(claim, ctx);
        // Still names an in-scope binding after substitution (a range-typed reference,
        // a param) — a genuinely dependent claim. Like the call gate for dependent
        // params (docs/dependent-sorts.md), PROVE it from the referenced bindings'
        // refinements; never stamp a runtime check ("no runtime refinement checks by
        // default"). Proved → discharged; otherwise a compile error as-is (the program
        // must narrow the reference so its sort entails the claim).
        if (resolved instanceof IrSort.Refined rr && mentionsBinding(rr.predicate())) {
            if (dischargesUnderScope(resolved, arg, ctx)) return null;
            throw new CompileException(
                    "let '" + l.name() + "' has a dependent declared sort " + render(resolved)
                            + " that cannot be proved from the referenced binding(s) in scope"
                            + (arg != null ? " (the value's sort is " + render(arg) + ")" : "")
                            + " — narrow the referenced binding's sort so it entails the claim.",
                    value.origin());
        }
        return switch (classify(arg, resolved, structs, actx)) {
            case FITS -> null;  // discharged — provable fit, no runtime check
            case DISJOINT -> throw new CompileException(
                    "let '" + l.name() + "' can never satisfy its declared sort "
                            + render(resolved) + " — the value's sort is "
                            + render(arg) + ", which is disjoint",
                    value.origin());
            // §1d (roadmap §1d): no silent runtime stamp. The parametric-Stream claim (the sole
            // sanctioned defer) already returned early above; an unprovable claim here is a compile
            // error — the value's sort must be narrowed to entail the claim.
            case UNKNOWN -> throw new CompileException(
                    "let '" + l.name() + "' cannot be proved to satisfy its declared sort "
                            + render(resolved) + " — the value's sort is " + render(arg)
                            + "; narrow the value so its sort entails the claim.",
                    value.origin());
        };
    }

    /**
     * Proves a dependent claim from the scope: {@code discharge(hyps, goal)} where the
     * goal is the claim predicate and the hypotheses are the value's own narrowing plus
     * each referenced binding's refinement (its {@code @} rebound to the binding's name).
     * {@code [Int:@>=x]} on value {@code 7} under {@code x:[Int:@<=7]} discharges via
     * {@code [Self==7, x<=7] ⊢ Self>=x}. Reuses the call gate's integer engine
     * ({@link BoundAnalysis#discharge}); anything outside its fragment (a non-Int base, a
     * reference with no usable refinement) simply fails to prove and is rejected — honest,
     * never a false accept.
     */
    private static boolean dischargesUnderScope(
            IrSort claim, IrSort arg, InferenceContext ctx) {
        if (!(claim instanceof IrSort.Refined ref)) return false;
        try {
            SymExpr goal = IrCompiler.compileSymExpr(ref.predicate());
            List<SymExpr> hyps = new ArrayList<>();
            if (arg instanceof IrSort.Refined ar) {
                hyps.add(IrCompiler.compileSymExpr(ar.predicate()));
            }
            for (String name : freeBindingNames(ref.predicate())) {
                IrSort rt = ctx.typeEnv().get(name);
                if (rt == null) rt = topLevelLetNarrowing(name, ctx);
                if (rt instanceof IrSort.Refined rr) {
                    hyps.add(Substitute.applySelf(
                            IrCompiler.compileSymExpr(rr.predicate()), SymExpr.var(name)));
                }
            }
            return BoundAnalysis.discharge(hyps, goal);
        } catch (CompileException outsideFragment) {
            return false;
        }
    }

    /** Whether a claim predicate references any in-scope binding (a free {@code Var}
     *  or a 0-arg {@code Call} — a top-level let) — i.e. it is dependent. */
    private static boolean mentionsBinding(IrExpr predicate) {
        return !freeBindingNames(predicate).isEmpty();
    }

    /** The names a claim predicate references (free {@code Var}s and 0-arg {@code Call}s). */
    private static Set<String> freeBindingNames(IrExpr predicate) {
        Set<String> names = new LinkedHashSet<>();
        collectBindingNames(predicate, names);
        return names;
    }

    private static void collectBindingNames(IrExpr e, Set<String> out) {
        switch (e) {
            case IrExpr.Var v -> out.add(v.name());
            case IrExpr.Call c when c.args().isEmpty() -> out.add(c.functionName());
            case IrExpr.BinOp op -> {
                collectBindingNames(op.left(), out);
                collectBindingNames(op.right(), out);
            }
            case IrExpr.FieldAccess fa -> collectBindingNames(fa.base(), out);
            default -> { }
        }
    }

    /**
     * Substitutes in-scope pinned bindings into a refined claim's predicate so a
     * dependent refinement can be decided statically: {@code [Int:@>=x]} with
     * {@code x} pinned to {@code 5} becomes {@code [Int:@>=5]}. {@code @} (Self) is
     * untouched — only value-level name references are replaced. A non-refined claim,
     * or one that names nothing pinned, returns unchanged.
     */
    private static IrSort substituteScope(IrSort claim, InferenceContext ctx) {
        if (!(claim instanceof IrSort.Refined ref)) return claim;
        IrExpr sub = substituteRefs(ref.predicate(), ctx);
        return sub == ref.predicate() ? claim
                : new IrSort.Refined(ref.name(), ref.typeArgs(), sub, ref.origin());
    }

    /** Replaces each {@code Var} (or 0-arg {@code Call} — a top-level let) whose
     *  binding the scope pins with that pinned value. */
    private static IrExpr substituteRefs(IrExpr e, InferenceContext ctx) {
        return switch (e) {
            case IrExpr.Var v -> {
                IrExpr pin = pinnedValue(v.name(), ctx);
                yield pin != null ? pin : v;
            }
            case IrExpr.Call c when c.args().isEmpty() -> {
                IrExpr pin = pinnedValue(c.functionName(), ctx);
                yield pin != null ? pin : c;
            }
            case IrExpr.BinOp op -> {
                IrExpr l = substituteRefs(op.left(), ctx);
                IrExpr r = substituteRefs(op.right(), ctx);
                yield l == op.left() && r == op.right() ? op
                        : new IrExpr.BinOp(op.op(), l, r, op.origin());
            }
            case IrExpr.FieldAccess fa -> {
                IrExpr b = substituteRefs(fa.base(), ctx);
                yield b == fa.base() ? fa : new IrExpr.FieldAccess(b, fa.fieldName(), fa.origin());
            }
            default -> e;
        };
    }

    /**
     * The value an in-scope binding is pinned to, when its inferred narrowing is a
     * singleton {@code [T:@==V]} with a Self-free {@code V}; else {@code null}. Reads
     * {@code typeEnv} directly — the same source {@link NarrowingInference} uses for a
     * {@code Var} — so params and computed locals (narrowed to a range, not a point)
     * correctly yield {@code null} and stay residual.
     */
    private static IrExpr pinnedValue(String name, InferenceContext ctx) {
        IrSort n = ctx.typeEnv().get(name);
        if (n == null) n = topLevelLetNarrowing(name, ctx);
        if (!(n instanceof IrSort.Refined r)) return null;
        if (!(r.predicate() instanceof IrExpr.BinOp op) || op.op() != IrExpr.Op.EQ) return null;
        if (op.left() instanceof IrExpr.SelfRef && !mentionsSelf(op.right())) return op.right();
        if (op.right() instanceof IrExpr.SelfRef && !mentionsSelf(op.left())) return op.left();
        return null;
    }

    /**
     * The inferred narrowing of a top-level {@code let} (a 0-arg function at this
     * stage, before lowering wraps them into {@code LetIn}s) — {@code let x = 5}
     * narrows to {@code [Int:@==5]}. Only a single, param-less, top-level-let
     * overload qualifies; anything else (a real function, an overload set) yields
     * {@code null} and stays residual.
     */
    private static IrSort topLevelLetNarrowing(String name, InferenceContext ctx) {
        List<IrStmt.FunctionDecl> ovs = ctx.overloads().get(name);
        if (ovs == null || ovs.size() != 1) return null;
        IrStmt.FunctionDecl fd = ovs.get(0);
        if (!fd.topLevelLet() || !fd.params().isEmpty()) return null;
        return TypeSystem.standard().infer(fd.body(), ctx);
    }

    private static boolean mentionsSelf(IrExpr e) {
        return switch (e) {
            case IrExpr.SelfRef s -> true;
            case IrExpr.BinOp op -> mentionsSelf(op.left()) || mentionsSelf(op.right());
            case IrExpr.FieldAccess fa -> mentionsSelf(fa.base());
            default -> false;
        };
    }

    private static IrExpr gateRecord(
            IrExpr.Record r, InferenceContext ctx, Map<String, IrSort.Structural> structs,
            AssignabilityContext actx, Map<Origin.Span, IrSort> lens) throws CompileException {
        Map<String, IrExpr> members = new LinkedHashMap<>();
        for (Map.Entry<String, IrExpr> en : r.members().entrySet()) {
            members.put(en.getKey(), rewriteExpr(en.getValue(), ctx, structs, actx, lens));
        }
        IrSort.Structural decl = r.typeName() == null ? null : structs.get(r.typeName());
        if (decl == null && r.typeName() != null && NativeConstructors.has(r.typeName())) {
            // Native constructors gate like declared structs — their registered
            // shape is the claim. Bare primitive fields ARE gated here (unlike
            // user structs): a native field signature is authoritative, so a
            // provably wrong-based argument (Decimal(2.5, 1)) is a compile
            // error rather than the construct map's runtime complaint.
            decl = NativeConstructors.get(r.typeName()).shape();
        }
        if (decl == null) {
            // Anonymous / tuple-sentinel / unregistered — no declared claim to gate.
            return new IrExpr.Record(r.typeName(), members, r.origin());
        }
        boolean nativeTarget = NativeConstructors.has(r.typeName());
        Map<String, IrSort> checks = new LinkedHashMap<>();
        for (Map.Entry<String, IrExpr> en : members.entrySet()) {
            IrSort field = decl.members().get(en.getKey());
            if (field == null) continue;  // arity/field mismatches are the parser's beat
            if (!nativeTarget && !gated(field, structs)) continue;  // bare unregistered names stay lenient
            IrSort arg = effectiveArg(en.getValue(), lens, ctx, structs);
            switch (classify(arg, field, structs, actx)) {
                case FITS -> { }
                case DISJOINT -> throw new CompileException(
                        "Constructor argument '" + en.getKey() + "' of '" + r.typeName()
                                + "' can never satisfy its declared sort "
                                + render(field) + " — the argument's sort is "
                                + render(arg) + ", which is disjoint",
                        en.getValue().origin());
                // §1d: the only sanctioned deferral is the parametric-Stream element check (a
                // genuinely statically-undecidable element type). Everything else unprovable is a
                // compile error — no silent runtime stamp.
                case UNKNOWN -> {
                    if (isParametricStream(field)) {
                        checks.put(en.getKey(), field);
                    } else {
                        throw new CompileException(
                                "Constructor argument '" + en.getKey() + "' of '" + r.typeName()
                                        + "' cannot be proved to satisfy its declared sort "
                                        + render(field) + " — the argument's sort is " + render(arg)
                                        + "; narrow the value so its sort entails the field.",
                                en.getValue().origin());
                    }
                }
            }
        }
        deriveAndCheckTypeParams(r, decl, members, ctx, structs);
        return new IrExpr.Record(r.typeName(), members, checks, r.origin());
    }

    /**
     * Type-parameter derivation at construction (docs/type-parameters.md §3.1,
     * §3.3): for a parametric struct, each `type T` is recovered by matching the
     * field sorts that mention it against the constructor arguments' concrete
     * sorts — "the field is the witness." A parameter the arguments bind two
     * different ways (`Pair(1, true)` — `T` as Int and as Bool) is a compile
     * error: the arguments disagree on the type. Pure check; no rewrite — the
     * value already carries its concrete field types at runtime.
     */
    private static void deriveAndCheckTypeParams(
            IrExpr.Record r, IrSort.Structural decl, Map<String, IrExpr> members,
            InferenceContext ctx, Map<String, IrSort.Structural> structs)
            throws CompileException {
        if (decl.typeParams().isEmpty()) return;
        Set<String> params = decl.typeParams().keySet();
        Map<String, String> bound = new LinkedHashMap<>();
        for (Map.Entry<String, IrExpr> en : members.entrySet()) {
            IrSort field = decl.members().get(en.getKey());
            if (field == null) continue;
            IrSort arg = argSort(en.getValue(), ctx, structs);
            if (arg == null) continue;
            unifyTypeParams(field, arg, params, bound, r);
        }
    }

    /**
     * One-directional match of a field sort (the lens, which may mention type
     * parameters) against an argument's concrete sort, recording each
     * parameter's concrete base name into {@code bound} and throwing when a
     * parameter is bound two incompatible ways. Bare `T` binds to the argument's
     * base; a parametric application `Element[T]` unifies positionally against a
     * concrete `Element[Int]`. Shapes it can't match are skipped (best-effort —
     * the derivation only needs one witness per parameter).
     */
    private static void unifyTypeParams(
            IrSort lens, IrSort concrete, Set<String> params,
            Map<String, String> bound, IrExpr.Record r) throws CompileException {
        if (!(lens instanceof IrSort.Named ln)) return;
        if (ln.typeArgs().isEmpty() && params.contains(ln.name())) {
            String c = baseName(concrete);
            if (c == null) return;
            String prev = bound.putIfAbsent(ln.name(), c);
            if (prev != null && !prev.equals(c)) {
                throw new CompileException(
                        "Type parameter '" + ln.name() + "' of '" + r.typeName()
                                + "' is bound to both '" + prev + "' and '" + c
                                + "' — the constructor arguments disagree on the type",
                        r.origin());
            }
            return;
        }
        // Parametric application: `Element[T]` vs a concrete `Element[Int]` —
        // unify the type arguments positionally when heads and arity agree.
        if (!ln.typeArgs().isEmpty() && concrete instanceof IrSort.Named cn
                && cn.name().equals(ln.name())
                && cn.typeArgs().size() == ln.typeArgs().size()) {
            for (int i = 0; i < ln.typeArgs().size(); i++) {
                unifyTypeParams(ln.typeArgs().get(i), cn.typeArgs().get(i), params, bound, r);
            }
        }
    }

    /**
     * Whether {@code value}'s concrete struct is-a {@code claim}'s base — a demotion widen
     * (`Point3D → Point`). §6.5 (docs/type-system-roadmap.md): a proven widen the binding
     * accepts as a VIEW (the concrete value is retained, not projected/forgotten). One level
     * (a direct declared base), matching the prior projection's scope.
     */
    private static boolean demotesToClaim(
            IrExpr value, IrSort claim, InferenceContext ctx,
            Map<String, IrSort.Structural> structs) {
        String claimBase = baseName(claim);
        if (claimBase == null || !structs.containsKey(claimBase)) return false;
        IrSort arg = TypeSystem.standard().infer(value, ctx);
        String argBase = arg == null ? null : baseName(arg);
        if (argBase == null || argBase.equals(claimBase)) return false;
        IrSort.Structural from = structs.get(argBase);
        if (from == null || from.baseSort() == null) return false;
        return claimBase.equals(baseName(from.baseSort()));
    }

    /** The argument's narrowing; a named-record argument claims its own name. */
    private static IrSort argSort(
            IrExpr arg, InferenceContext ctx, Map<String, IrSort.Structural> structs) {
        IrSort inferred = TypeSystem.standard().infer(arg, ctx);
        if (inferred != null) return inferred;
        if (arg instanceof IrExpr.Record rec && rec.typeName() != null
                && structs.containsKey(rec.typeName())) {
            return IrSort.named(rec.typeName());
        }
        return null;
    }

    /**
     * Is this field sort worth gating at all? Bare unregistered names
     * (unrefined primitives, traits) keep today's leniency; refinements,
     * registered struct names, and compositions of them are claims the
     * gate judges. (Bare-primitive legality is decided trait-free at the
     * parser via {@code Assignability} — roadmap §4.5 item 1 — not here,
     * so the gate never faces an undetermined-base primitive.)
     */
    private static boolean gated(IrSort field, Map<String, IrSort.Structural> structs) {
        return switch (field) {
            case IrSort.Refined ref -> true;
            case IrSort.Named n -> structs.containsKey(n.name());
            case IrSort.Trait t -> isParametricStream(t);
            case IrSort.Union u -> u.branches().stream().anyMatch(b -> gated(b, structs));
            case IrSort.Intersection i -> i.branches().stream().anyMatch(b -> gated(b, structs));
            default -> false;
        };
    }

    /**
     * A resolved parametric {@code Stream[T]} (bare or linker-qualified) — the only
     * trait whose parametric contract is checkable today (WAR(stream) §8.6: a stream
     * is a sequence of its element type). Other parametric traits carry their args but
     * get no invented invariant, so they are NOT gated here.
     */
    private static boolean isParametricStream(IrSort s) {
        if (s instanceof IrSort.Trait t && !t.typeArgs().isEmpty()) return isStreamName(t.name());
        if (s instanceof IrSort.Named n && !n.typeArgs().isEmpty()) return isStreamName(n.name());
        return false;
    }

    private static boolean isStreamName(String n) {
        return n != null && (n.equals("Stream") || n.endsWith("/Stream"));
    }

    /**
     * The argument's EFFECTIVE sort at its position — read from the pre-computed lens
     * ({@link EffectiveSortLens}), so the gate consumes the one materialized set of effective sorts
     * rather than recomputing (roadmap §1b, compute-once). Falls back to on-the-spot inference only
     * for a node the lens has no entry for (e.g. a synthesized node with no source span). Feeding the
     * projected effective sort (not a raw pin) is what lets a context-provable construction —
     * {@code Account(this.balance + n)} — discharge in {@link #classify}.
     */
    private static IrSort effectiveArg(
            IrExpr e, Map<Origin.Span, IrSort> lens, InferenceContext ctx,
            Map<String, IrSort.Structural> structs) {
        if (e.origin() != null && e.origin().span() != null) {
            IrSort eff = lens.get(e.origin().span());
            if (eff != null) return eff;
        }
        return argSort(e, ctx, structs);
    }

    private enum Fit { FITS, DISJOINT, UNKNOWN }

    /**
     * Three-way verdict of an argument sort against a field sort. Sound in
     * both decisive directions: FITS only when every argument value satisfies
     * the field sort; DISJOINT only when none can. Everything undecidable is
     * UNKNOWN — the runtime check's territory.
     */
    private static Fit classify(
            IrSort arg, IrSort field, Map<String, IrSort.Structural> structs,
            AssignabilityContext actx) {
        // Argument-side union FIRST: a union argument fits iff EVERY branch fits the field. This
        // must precede the field-side rule below — otherwise a union arg against a union field asks
        // "does the whole arg union fit some single field branch?", which no multi-member union
        // does, so reflexive union subsumption (U ⊑ U — e.g. a `pontif.poly` function returning the
        // closed AlgExpr union, fed to an AlgExpr-typed constructor field) wrongly reads UNKNOWN.
        if (arg instanceof IrSort.Union argU) {
            boolean allFit = true;
            boolean allDisjoint = true;
            for (IrSort b : argU.branches()) {
                Fit f = classify(b, field, structs, actx);
                allFit &= f == Fit.FITS;
                allDisjoint &= f == Fit.DISJOINT;
            }
            return allFit ? Fit.FITS : allDisjoint ? Fit.DISJOINT : Fit.UNKNOWN;
        }
        // Field-side composition: a (non-union) arg against a union field fits if any branch fits.
        if (field instanceof IrSort.Union u) {
            boolean allDisjoint = true;
            for (IrSort b : u.branches()) {
                Fit f = classify(arg, b, structs, actx);
                if (f == Fit.FITS) return Fit.FITS;
                if (f != Fit.DISJOINT) allDisjoint = false;
            }
            return allDisjoint ? Fit.DISJOINT : Fit.UNKNOWN;
        }
        if (field instanceof IrSort.Intersection i) {
            boolean allFit = true;
            for (IrSort b : i.branches()) {
                Fit f = classify(arg, b, structs, actx);
                if (f == Fit.DISJOINT) return Fit.DISJOINT;
                if (f != Fit.FITS) allFit = false;
            }
            return allFit ? Fit.FITS : Fit.UNKNOWN;
        }
        if (arg == null) return Fit.UNKNOWN;

        String argBase = baseName(arg);
        String fieldBase = baseName(field);
        // A non-nominal or "_"-unknown base is genuinely undecidable — defer (never delegate a
        // guess to the engine, which would read "_" as a concrete unknown name and rule DISJOINT).
        if (argBase == null || fieldBase == null) return Fit.UNKNOWN;

        // NOMINAL LEG — the single decider (roadmap §4.3). Assignability answers whether the
        // argument's concrete type can inhabit the field's *nominal* type: widen (`Point3D → Point`),
        // trait satisfaction, union membership, and the `Int → Decimal` numeric coercion. This is
        // strictly stronger than the retired base-name-equality compare, which missed valid widens
        // and — for a non-satisfying trait field — stamped an unprovable runtime check (a §1d lie).
        // The refinement predicate (if any) is judged in the leg below, so the nominal question
        // ignores refinements on BOTH sides — a refined value's fit against its own bare base is a
        // pure widen (EXACT), and any predicate mismatch is the refinement leg's call, not a nominal
        // DISJOINT. (Comparing a refined arg against a bare base via the full engine would wrongly
        // read the refined/bare mismatch as not-is-a for primitive tags.)
        Assignment nominal = Assignability.assign(stripRefinement(arg), stripRefinement(field), actx);
        if (nominal == Assignment.ILLEGAL || nominal == Assignment.NEEDS_CAST) {
            // No implicit value of the argument's type inhabits the field's nominal type (ILLEGAL),
            // and none is bridged by an implicit coercion (NEEDS_CAST wants an explicit cast the
            // author didn't write) — a provable miss. §1d: this is the honest compile-time DISJOINT
            // that replaces the old UNKNOWN→runtime-stamp for a non-satisfying trait / sibling field.
            return Fit.DISJOINT;
        }
        // Nominal fits (EXACT / WIDEN / COERCE). An unrefined field accepts the whole nominal.
        if (!(field instanceof IrSort.Refined refinedField)) return Fit.FITS;

        // Refined field — the three-way refinement leg. The integer interval kernel
        // (PredicateArithmetic) decides Int predicates precisely (FITS/DISJOINT); outside its
        // fragment (a non-Int domain such as Decimal, or a genuine overlap) it abstains, and we fall
        // to the Int+Decimal-capable Refinements kernel — the SAME engine Assignability delegates its
        // refined leaf to — for a provable fit before conceding UNKNOWN. So a Decimal literal fit
        // (`[Decimal:@==100.0]` ⊑ `[Decimal:@>=0]`) discharges here (roadmap §4).
        SymExpr fieldPred;
        Sort domain;
        try {
            fieldPred = IrCompiler.compileSymExpr(refinedField.predicate());
            domain = IrCompiler.compileSort(arg);
        } catch (CompileException outsideFragment) {
            return refinementFits(arg, field) ? Fit.FITS : Fit.UNKNOWN;
        }
        // FITS: no argument value escapes the field predicate (integer kernel).
        if (PredicateArithmetic.complement(fieldPred, domain)
                instanceof ComplementResult.Computed computed
                && PredicateArithmetic.satisfiable(computed.predicate(), domain)
                        instanceof SatResult.No) {
            return Fit.FITS;
        }
        // DISJOINT: no argument value satisfies it (integer kernel).
        if (PredicateArithmetic.satisfiable(fieldPred, domain) instanceof SatResult.No) {
            return Fit.DISJOINT;
        }
        return refinementFits(arg, field) ? Fit.FITS : Fit.UNKNOWN;
    }

    /** Provable refinement fit {@code arg ⊑ field} via the Int+Decimal-capable {@link Refinements}
     *  kernel (the engine {@link Assignability} delegates its refined leaf to) — decides Decimal
     *  predicates the integer interval kernel can't. Abstains (false) outside the kernel or on any
     *  compile failure; never a false fit. */
    private static boolean refinementFits(IrSort arg, IrSort field) {
        try {
            return Refinements.imply(IrCompiler.compileSort(arg), IrCompiler.compileSort(field),
                    new Simplifier(List.of())).isPassed();
        } catch (Exception abstain) {
            return false;
        }
    }

    /** The bare nominal base of a sort — a refined sort's underlying named base, else the sort itself.
     *  Used to ask the nominal-subtype engine the base-name question without the refinement predicate,
     *  which the gate's refinement leg judges separately. */
    private static IrSort stripRefinement(IrSort s) {
        return s instanceof IrSort.Refined r
                ? new IrSort.Named(r.name(), r.typeArgs(), r.origin())
                : s;
    }

    private static String baseName(IrSort sort) {
        return switch (sort) {
            case IrSort.Named n -> n.name().equals("_") ? null : n.name();
            case IrSort.Refined r -> r.name();
            case IrSort.Structural s -> s.name();
            default -> null;
        };
    }

    private static String render(IrSort sort) {
        if (sort == null) return "(not statically known)";
        try {
            return IrCompiler.compileSort(sort).toString();
        } catch (CompileException e) {
            String base = baseName(sort);
            return base != null ? base : sort.getClass().getSimpleName();
        }
    }
}
