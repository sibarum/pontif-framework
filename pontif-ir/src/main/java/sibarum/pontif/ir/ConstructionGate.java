package sibarum.pontif.ir;

import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.predicates.ComplementResult;
import sibarum.pontif.predicates.PredicateArithmetic;
import sibarum.pontif.predicates.SatResult;

import java.util.ArrayList;
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
 * all (today's behavior); the Int→Decimal embedding is never ruled
 * disjoint; and a field sort the runtime cannot satisfy-check (Method,
 * Dispatch, inline structural) is never stamped.
 *
 * <p>Runs after {@link AggregatePromotion} (anonymous literals are
 * already stamped with their struct names) and {@link DecimalPromotion}
 * (Int literals at Decimal fields are already promoted), before
 * {@link SortChecker}.
 */
final class ConstructionGate {

    private static final Set<String> PRIMITIVES = Set.of("Int", "Bool", "Char", "Decimal");

    private ConstructionGate() {}

    static IrModule rewrite(IrModule module) throws CompileException {
        Map<String, IrSort.Structural> structs = TypeRegistry.collect(module);
        InferenceContext base = InferenceContext.fromModule(module);
        List<IrStmt> out = new ArrayList<>(module.statements().size());
        for (IrStmt stmt : module.statements()) {
            out.add(switch (stmt) {
                case IrStmt.FunctionDecl fd -> rewriteFunction(fd, base, structs);
                case IrStmt.TraitImpl ti -> {
                    List<IrStmt.FunctionDecl> methods = new ArrayList<>(ti.methods().size());
                    for (IrStmt.FunctionDecl m : ti.methods()) {
                        methods.add(rewriteFunction(m, base, structs));
                    }
                    List<IrStmt.FunctionDecl> attrs = new ArrayList<>(ti.attributeProducers().size());
                    for (IrStmt.FunctionDecl a : ti.attributeProducers()) {
                        attrs.add(rewriteFunction(a, base, structs));
                    }
                    yield new IrStmt.TraitImpl(ti.typeName(), ti.traitName(), methods, attrs, ti.origin());
                }
                default -> stmt;  // TypeAlias / Proof / Requires / Exports / NoOp
            });
        }
        IrExpr main = module.main() == null
                ? null
                : rewriteExpr(module.main(), base, structs);
        return new IrModule(module.name(), out, main);
    }

    private static IrStmt.FunctionDecl rewriteFunction(
            IrStmt.FunctionDecl fd, InferenceContext base,
            Map<String, IrSort.Structural> structs) throws CompileException {
        InferenceContext ctx = base;
        for (IrParam p : fd.params()) {
            if (p.sort() != null) ctx = ctx.withVar(p.name(), p.sort());
        }
        return new IrStmt.FunctionDecl(
                fd.name(), fd.params(), fd.returnSort(),
                rewriteExpr(fd.body(), ctx, structs), fd.origin(), fd.topLevelLet());
    }

    /**
     * Structural rewrite threading the narrowing context the same way
     * {@link NarrowingInference} extends it: let bindings carry the value's
     * inferred narrowing (falling back to the declared sort), match arms
     * over a variable scrutinee carry the arm's pattern.
     */
    private static IrExpr rewriteExpr(
            IrExpr e, InferenceContext ctx,
            Map<String, IrSort.Structural> structs) throws CompileException {
        return switch (e) {
            case IrExpr.Lit l -> l;
            case IrExpr.Dec d -> d;
            case IrExpr.Chr c -> c;
            case IrExpr.Bool b -> b;
            case IrExpr.Var v -> v;
            case IrExpr.SelfRef s -> s;
            case IrExpr.DispatchRef d -> d;
            case IrExpr.BinOp op -> new IrExpr.BinOp(
                    op.op(),
                    rewriteExpr(op.left(), ctx, structs),
                    rewriteExpr(op.right(), ctx, structs),
                    op.origin());
            case IrExpr.LetIn l -> {
                IrExpr value = rewriteExpr(l.value(), ctx, structs);
                // S3: demotion coercion. If the value's sort is a struct that
                // declares a base demoting to the claim, project it (run the
                // morphism, drop unmentioned fields — a clean forget, no tag)
                // instead of letting the gate reject it as disjoint.
                IrExpr coerced = maybeDemote(value, l.claim(), ctx, structs);
                boolean demoted = coerced != value;
                IrSort inferred = NarrowingInference.infer(demoted ? coerced : l.value(), ctx);
                IrSort bound = inferred != null ? inferred
                        : demoted ? l.claim() : l.declaredSort();
                InferenceContext bodyCtx = bound != null ? ctx.withVar(l.name(), bound) : ctx;
                IrSort declared = demoted ? l.claim() : l.declaredSort();
                yield new IrExpr.LetIn(l.name(), declared, coerced,
                        rewriteExpr(l.body(), bodyCtx, structs), l.origin(),
                        gateClaim(l, coerced, ctx, structs));
            }
            case IrExpr.Call c -> {
                List<IrExpr> args = new ArrayList<>(c.args().size());
                for (IrExpr a : c.args()) args.add(rewriteExpr(a, ctx, structs));
                yield new IrExpr.Call(c.functionName(), args, c.origin());
            }
            case IrExpr.Lambda lam -> {
                InferenceContext bodyCtx = ctx;
                for (IrParam p : lam.params()) {
                    if (p.sort() != null) bodyCtx = bodyCtx.withVar(p.name(), p.sort());
                }
                yield new IrExpr.Lambda(lam.params(), lam.returnSort(),
                        rewriteExpr(lam.body(), bodyCtx, structs), lam.origin());
            }
            case IrExpr.Apply app -> {
                List<IrExpr> args = new ArrayList<>(app.args().size());
                for (IrExpr a : app.args()) args.add(rewriteExpr(a, ctx, structs));
                yield new IrExpr.Apply(rewriteExpr(app.fn(), ctx, structs), args, app.origin());
            }
            case IrExpr.Match m -> {
                IrExpr scrutinee = rewriteExpr(m.scrutinee(), ctx, structs);
                List<IrExpr.MatchBranch> branches = new ArrayList<>(m.branches().size());
                for (IrExpr.MatchBranch b : m.branches()) {
                    InferenceContext armCtx =
                            m.scrutinee() instanceof IrExpr.Var v && b.pattern() != null
                                    ? ctx.withVar(v.name(), b.pattern())
                                    : ctx;
                    branches.add(new IrExpr.MatchBranch(
                            b.pattern(), rewriteExpr(b.result(), armCtx, structs)));
                }
                yield new IrExpr.Match(scrutinee, branches, m.origin());
            }
            case IrExpr.Record r -> gateRecord(r, ctx, structs);
            case IrExpr.FieldAccess fa -> new IrExpr.FieldAccess(
                    rewriteExpr(fa.base(), ctx, structs), fa.fieldName(), fa.origin());
        };
    }

    /**
     * The claim rule's binding half: a declared sort at a let is judged
     * against the value's narrowing exactly like a constructor argument.
     * Returns the claim the runtime must still check (UNKNOWN verdict),
     * null when discharged (FITS) or absent, and throws on a provable miss.
     *
     * <p>Same leniencies as the record gate, deliberately: a bare
     * unregistered claim ({@code let x:Int = …}) is not gated at all
     * (today's behavior — the parser's base check is the only judge), and
     * a claim the runtime cannot satisfy-check is never kept.
     */
    private static IrSort gateClaim(
            IrExpr.LetIn l, IrExpr value, InferenceContext ctx,
            Map<String, IrSort.Structural> structs) throws CompileException {
        IrSort claim = l.claim();
        if (claim == null || !gated(claim, structs)) return null;
        IrSort arg = argSort(value, ctx, structs);
        return switch (classify(arg, claim, structs)) {
            case FITS -> null;  // discharged — provable fit, no runtime check
            case DISJOINT -> throw new CompileException(
                    "let '" + l.name() + "' can never satisfy its declared sort "
                            + render(claim) + " — the value's sort is "
                            + render(arg) + ", which is disjoint",
                    value.origin());
            case UNKNOWN -> runtimeCheckable(claim, structs) ? claim : null;
        };
    }

    private static IrExpr gateRecord(
            IrExpr.Record r, InferenceContext ctx,
            Map<String, IrSort.Structural> structs) throws CompileException {
        Map<String, IrExpr> members = new LinkedHashMap<>();
        for (Map.Entry<String, IrExpr> en : r.members().entrySet()) {
            members.put(en.getKey(), rewriteExpr(en.getValue(), ctx, structs));
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
            IrSort arg = argSort(en.getValue(), ctx, structs);
            switch (classify(arg, field, structs)) {
                case FITS -> { }
                case DISJOINT -> throw new CompileException(
                        "Constructor argument '" + en.getKey() + "' of '" + r.typeName()
                                + "' can never satisfy its declared sort "
                                + render(field) + " — the argument's sort is "
                                + render(arg) + ", which is disjoint",
                        en.getValue().origin());
                case UNKNOWN -> {
                    if (runtimeCheckable(field, structs)) checks.put(en.getKey(), field);
                }
            }
        }
        return new IrExpr.Record(r.typeName(), members, checks, r.origin());
    }

    /**
     * Demotion coercion (S3): if {@code value}'s static sort is a struct that
     * declares a base demoting to {@code claim}'s base, replace it with the
     * morphism projection — a fresh {@code Base(...)} record built from the
     * value's fields, dropping fields the base doesn't mention. A clean forget:
     * no surviving brand. Returns {@code value} unchanged when it isn't a
     * demotion.
     */
    private static IrExpr maybeDemote(
            IrExpr value, IrSort claim, InferenceContext ctx,
            Map<String, IrSort.Structural> structs) {
        if (claim == null) return value;
        String claimBase = baseName(claim);
        IrSort.Structural to = claimBase == null ? null : structs.get(claimBase);
        if (to == null) return value;  // claim isn't a declared struct
        IrSort arg = NarrowingInference.infer(value, ctx);
        String argBase = arg == null ? null : baseName(arg);
        if (argBase == null || argBase.equals(claimBase)) return value;
        IrSort.Structural from = structs.get(argBase);
        if (from == null || from.baseSort() == null) return value;
        if (!claimBase.equals(baseName(from.baseSort()))) return value;  // doesn't demote here
        return projectDemotion(value, from, to);
    }

    /**
     * Builds {@code Base(...)} from {@code value} per {@code from}'s demotion
     * morphism: each base field's value is the morphism's {@code @.field == RHS}
     * right-hand side, with the deriving struct's param names rewritten to field
     * reads on {@code value}. Unmentioned base fields fall back to a same-named
     * projection.
     */
    private static IrExpr projectDemotion(
            IrExpr value, IrSort.Structural from, IrSort.Structural to) {
        Map<String, IrExpr> rhsByField = new LinkedHashMap<>();
        if (from.baseSort() instanceof IrSort.Refined r) {
            collectFieldRhs(r.predicate(), rhsByField);
        }
        Map<String, IrExpr> fields = new LinkedHashMap<>();
        for (String f : to.members().keySet()) {
            IrExpr rhs = rhsByField.get(f);
            fields.put(f, rhs != null
                    ? substituteParamReads(rhs, value, from.members().keySet())
                    : new IrExpr.FieldAccess(value, f, value.origin()));
        }
        return new IrExpr.Record(to.name(), fields, value.origin());
    }

    /** Records each {@code @.field == RHS} conjunct as field -> RHS. */
    private static void collectFieldRhs(IrExpr pred, Map<String, IrExpr> out) {
        if (pred instanceof IrExpr.BinOp op) {
            switch (op.op()) {
                case AND -> {
                    collectFieldRhs(op.left(), out);
                    collectFieldRhs(op.right(), out);
                }
                case EQ -> {
                    String lf = selfFieldName(op.left());
                    if (lf != null) { out.put(lf, op.right()); return; }
                    String rf = selfFieldName(op.right());
                    if (rf != null) out.put(rf, op.left());
                }
                default -> { }
            }
        }
    }

    private static String selfFieldName(IrExpr e) {
        return e instanceof IrExpr.FieldAccess fa && fa.base() instanceof IrExpr.SelfRef
                ? fa.fieldName() : null;
    }

    /** Rewrites {@code Var(p)} (p a deriving-struct field) to {@code value.p}. */
    private static IrExpr substituteParamReads(IrExpr e, IrExpr value, Set<String> params) {
        return switch (e) {
            case IrExpr.Var v -> params.contains(v.name())
                    ? new IrExpr.FieldAccess(value, v.name(), v.origin()) : v;
            case IrExpr.BinOp op -> new IrExpr.BinOp(op.op(),
                    substituteParamReads(op.left(), value, params),
                    substituteParamReads(op.right(), value, params), op.origin());
            case IrExpr.FieldAccess fa -> new IrExpr.FieldAccess(
                    substituteParamReads(fa.base(), value, params), fa.fieldName(), fa.origin());
            case IrExpr.Call c -> {
                List<IrExpr> args = new ArrayList<>(c.args().size());
                for (IrExpr a : c.args()) args.add(substituteParamReads(a, value, params));
                yield new IrExpr.Call(c.functionName(), args, c.origin());
            }
            default -> e;
        };
    }

    /** The argument's narrowing; a named-record argument claims its own name. */
    private static IrSort argSort(
            IrExpr arg, InferenceContext ctx, Map<String, IrSort.Structural> structs) {
        IrSort inferred = NarrowingInference.infer(arg, ctx);
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
     * gate judges.
     */
    private static boolean gated(IrSort field, Map<String, IrSort.Structural> structs) {
        return switch (field) {
            case IrSort.Refined ref -> true;
            case IrSort.Named n -> structs.containsKey(n.name());
            case IrSort.Union u -> u.branches().stream().anyMatch(b -> gated(b, structs));
            case IrSort.Intersection i -> i.branches().stream().anyMatch(b -> gated(b, structs));
            default -> false;
        };
    }

    /**
     * Can the runtime decide this sort against a constructed value? Refined
     * and named (registered or primitive) sorts and their compositions go
     * through {@code Refinements.satisfies}; Method/Dispatch/inline-structural
     * sorts would need value shapes the checker doesn't convert — never stamp
     * those (a miss, not a false claim).
     */
    private static boolean runtimeCheckable(IrSort field, Map<String, IrSort.Structural> structs) {
        return switch (field) {
            case IrSort.Refined ref -> true;
            case IrSort.Named n -> true;
            case IrSort.Union u ->
                    u.branches().stream().allMatch(b -> runtimeCheckable(b, structs));
            case IrSort.Intersection i ->
                    i.branches().stream().allMatch(b -> runtimeCheckable(b, structs));
            default -> false;
        };
    }

    private enum Fit { FITS, DISJOINT, UNKNOWN }

    /**
     * Three-way verdict of an argument sort against a field sort. Sound in
     * both decisive directions: FITS only when every argument value satisfies
     * the field sort; DISJOINT only when none can. Everything undecidable is
     * UNKNOWN — the runtime check's territory.
     */
    private static Fit classify(
            IrSort arg, IrSort field, Map<String, IrSort.Structural> structs) {
        // Field-side composition first: a union fits if any branch fits.
        if (field instanceof IrSort.Union u) {
            boolean allDisjoint = true;
            for (IrSort b : u.branches()) {
                Fit f = classify(arg, b, structs);
                if (f == Fit.FITS) return Fit.FITS;
                if (f != Fit.DISJOINT) allDisjoint = false;
            }
            return allDisjoint ? Fit.DISJOINT : Fit.UNKNOWN;
        }
        if (field instanceof IrSort.Intersection i) {
            boolean allFit = true;
            for (IrSort b : i.branches()) {
                Fit f = classify(arg, b, structs);
                if (f == Fit.DISJOINT) return Fit.DISJOINT;
                if (f != Fit.FITS) allFit = false;
            }
            return allFit ? Fit.FITS : Fit.UNKNOWN;
        }
        if (arg == null) return Fit.UNKNOWN;
        // Argument-side composition: a union fits if every branch fits.
        if (arg instanceof IrSort.Union u) {
            boolean allFit = true;
            boolean allDisjoint = true;
            for (IrSort b : u.branches()) {
                Fit f = classify(b, field, structs);
                allFit &= f == Fit.FITS;
                allDisjoint &= f == Fit.DISJOINT;
            }
            return allFit ? Fit.FITS : allDisjoint ? Fit.DISJOINT : Fit.UNKNOWN;
        }

        String argBase = baseName(arg);
        String fieldBase = baseName(field);
        if (argBase == null || fieldBase == null) return Fit.UNKNOWN;
        if (!argBase.equals(fieldBase)) {
            // The lossless Int→Decimal embedding: every Int inhabits an
            // UNREFINED Decimal (provable fit — no runtime check); against a
            // refined Decimal the predicate still decides at runtime.
            if (argBase.equals("Int") && fieldBase.equals("Decimal")) {
                return field instanceof IrSort.Refined ? Fit.UNKNOWN : Fit.FITS;
            }
            boolean argConcrete = PRIMITIVES.contains(argBase) || structs.containsKey(argBase);
            boolean fieldConcrete = PRIMITIVES.contains(fieldBase) || structs.containsKey(fieldBase);
            return argConcrete && fieldConcrete ? Fit.DISJOINT : Fit.UNKNOWN;
        }
        // Same base. An unrefined field accepts the whole base.
        if (!(field instanceof IrSort.Refined refinedField)) return Fit.FITS;

        SymExpr fieldPred;
        Sort domain;
        try {
            fieldPred = IrCompiler.compileSymExpr(refinedField.predicate());
            domain = IrCompiler.compileSort(arg);
        } catch (CompileException outsideFragment) {
            return Fit.UNKNOWN;
        }
        // FITS: no argument value escapes the field predicate.
        if (PredicateArithmetic.complement(fieldPred, domain)
                instanceof ComplementResult.Computed computed
                && PredicateArithmetic.satisfiable(computed.predicate(), domain)
                        instanceof SatResult.No) {
            return Fit.FITS;
        }
        // DISJOINT: no argument value satisfies it.
        if (PredicateArithmetic.satisfiable(fieldPred, domain) instanceof SatResult.No) {
            return Fit.DISJOINT;
        }
        return Fit.UNKNOWN;
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
