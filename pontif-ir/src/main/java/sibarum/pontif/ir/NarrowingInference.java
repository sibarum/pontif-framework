package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.Substitute;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.predicates.BoundAnalysis;
import sibarum.pontif.predicates.Interval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes the narrowed sort of an expression — what's known about the
 * expression's value-set beyond its declared sort.
 *
 * <p>Pure function over {@code (expression, ctx)}. No compile pass, no
 * IR mutation, no stored state — consumers call {@link #infer} when
 * they need a narrowing; results aren't cached. If memoization becomes
 * necessary later, it slots in without changing the signature.
 *
 * <p>Returns the narrowest sort the static fragment can derive, or
 * {@code null} when no narrowing beyond the declared sort is available.
 * Never throws. Callers fall back to the declared sort on {@code null}.
 *
 * <h2>Phase A + B + C coverage</h2>
 * <ul>
 *   <li>{@code Lit(n)} → {@code [Int:@==n]}
 *   <li>{@code Dec(d)} → {@code [Decimal:@==d]} (literal value is exact)
 *   <li>{@code Bool(b)} → {@code [Bool:@==b]}
 *   <li>{@code Var(x)} → env lookup (null if unbound)
 *   <li>{@code Match} → same-base union of arm result narrowings, taken
 *       under each arm's hypothesis (scrutinee {@code Var} narrowed by
 *       the arm's pattern)
 *   <li>{@code LetIn} → infer value's narrowing, extend env, infer body
 *   <li>{@code Call} → if overloads are populated in {@code ctx},
 *       runs {@link StaticDispatch} against the arg narrowings and
 *       returns the resolved overload's declared return sort. Falls
 *       back to {@code ctx.functionReturns()} on Unresolved or empty
 *       overloads.
 *   <li>{@code Record(TypeName, members)} → synthesizes
 *       {@code [TypeName:@.x==v_x & @.y==v_y & …]} from member narrowings
 *   <li>{@code FieldAccess(base, f)} → projects the field's narrowing out
 *       of a struct-refined base by extracting conjuncts that reference
 *       only {@code @.f} and substituting {@code @.f → @}
 *   <li>{@code BinOp} arithmetic ({@code + - *}) → bounds the expression
 *       with {@link BoundAnalysis} under the env's refinements and lifts
 *       the resulting {@link Interval} to an {@code [Int:…]} refinement
 *       (e.g. {@code x + 1} with {@code x:[Int:@>=1]} → {@code [Int:@>=2]})
 * </ul>
 *
 * <h2>Out of scope (returns null, fall back to declared)</h2>
 * <ul>
 *   <li>{@code BinOp} comparisons / boolean ops — they yield {@code Bool},
 *       not a bounded {@code Int}; narrowing a decidable predicate to a
 *       {@code Bool} singleton is discharge work, not bound analysis
 *   <li>{@code Apply}, {@code Lambda} — symbolic reduction of function
 *       application is simplifier work; deferred
 *   <li>{@code SelfRef} — only meaningful inside refinement predicates
 *   <li>Match-arm hypothesis intersection with prior narrowing — currently
 *       the arm's pattern <em>replaces</em> the var's narrowing. Phase D
 *       refinement.
 * </ul>
 */
public final class NarrowingInference {

    private NarrowingInference() {}

    /** Infers the narrowing of an expression under the given context. */
    public static IrSort infer(IrExpr expr, InferenceContext ctx) {
        return switch (expr) {
            case IrExpr.Lit l -> intSingleton(l.value());
            // A literal's value is known exactly — no engine needed — so a
            // decimal literal narrows to its singleton just like an integer one.
            // (Decimal *arithmetic* still doesn't narrow: the bound engine is
            // integer-only; this is only the literal.)
            case IrExpr.Dec d -> decimalSingleton(d.value());
            // Same for chars in the value slice — bare Char.
            case IrExpr.Chr c -> IrSort.named("Char");
            // Strings are bare String in the value slice — no narrows.
            case IrExpr.Str s -> IrSort.named("String");
            // A metareference's narrowing is its Dispatch shape; the return
            // stays "_" at this level (shallow — candidates aren't consulted).
            case IrExpr.DispatchRef d ->
                    new IrSort.Dispatch(d.keySorts(), IrSort.named("_"), d.origin());
            case IrExpr.Bool b -> boolSingleton(b.value());
            case IrExpr.Var v -> ctx.typeEnv().get(v.name());
            case IrExpr.LetIn let -> {
                IrSort valueNarrowing = infer(let.value(), ctx);
                IrSort bound = valueNarrowing != null ? valueNarrowing : let.declaredSort();
                yield infer(let.body(), ctx.withVar(let.name(), bound));
            }
            case IrExpr.Match m -> inferMatch(m, ctx);
            case IrExpr.Call c -> inferCall(c, ctx);
            case IrExpr.Record r -> inferRecord(r, ctx);
            case IrExpr.FieldAccess fa -> inferFieldAccess(fa, ctx);
            case IrExpr.BinOp op -> inferBinOp(op, ctx);
            // Deferred: see class doc.
            case IrExpr.Apply ignored -> null;
            case IrExpr.Lambda ignored -> null;
            case IrExpr.SelfRef ignored -> null;
            // Unresolved until MethodResolver; can't narrow its result here.
            case IrExpr.MethodCall ignored -> null;
            // REVISIT (docs/iteration.md §10): no result-narrowing for the
            // iteration construct yet (would infer the output tuple's sort).
            case IrExpr.Iterate ignored -> null;
        };
    }

    /**
     * Convenience: infer the return narrowing of a function declaration.
     * Seeds {@code ctx} with each param bound to its declared sort.
     */
    public static IrSort inferFunctionReturn(IrStmt.FunctionDecl fd, InferenceContext ctx) {
        InferenceContext seeded = ctx;
        for (IrParam p : fd.params()) {
            seeded = seeded.withVar(p.name(), p.sort());
        }
        return infer(fd.body(), seeded);
    }

    /**
     * Resolves a call's overload via {@link StaticDispatch} and infers the
     * callee's return narrowing from its <em>body</em> — the body-inference
     * fallback consumers reach for when {@link #infer}'s declared-return
     * answer is unrefined and a tighter narrowing would carry useful
     * inductive hypotheses (e.g. into a receipt-graph CallRef result sort).
     *
     * <p>Returns {@code null} when the call has no registered overloads,
     * static dispatch is unresolved, or the body's inferred sort isn't a
     * refinement.
     *
     * <p>Termination is safe by construction: {@link #infer}'s call case
     * never recurses into bodies — so the seeded body walk terminates at
     * recursive / mutually-recursive callees via the declared-return
     * fallback in {@link #inferCall}.
     */
    public static IrSort.Refined inferCallReturnFromBody(IrExpr.Call call, InferenceContext ctx) {
        List<IrStmt.FunctionDecl> overloads = ctx.overloads().get(call.functionName());
        if (overloads == null || overloads.isEmpty()) return null;
        List<IrSort> argNarrowings = new ArrayList<>(call.args().size());
        for (IrExpr arg : call.args()) {
            argNarrowings.add(infer(arg, ctx));
        }
        StaticDispatch.Result result = StaticDispatch.resolve(overloads, argNarrowings, ctx.sortRegistry());
        if (!(result instanceof StaticDispatch.Result.Resolved resolved)) {
            return null;
        }
        IrSort inferred = inferFunctionReturn(resolved.decl(), ctx);
        return inferred instanceof IrSort.Refined refined ? refined : null;
    }

    // --- Call (Phase D) ----------------------------------------------------

    /**
     * Call site: if the context has overloads registered for this name,
     * use {@link StaticDispatch} to pick the matching overload and
     * return its declared return sort. On Unresolved (kernel undecided,
     * no matches, or multiple ambiguous), fall back to
     * {@code ctx.functionReturns()} — the Phase A behavior.
     *
     * <p>Arg narrowings are inferred recursively. A null narrowing
     * means "unknown" and StaticDispatch treats it as residual.
     */
    private static IrSort inferCall(IrExpr.Call c, InferenceContext ctx) {
        List<IrStmt.FunctionDecl> overloads = ctx.overloads().get(c.functionName());
        if (overloads == null || overloads.isEmpty()) {
            return ctx.functionReturns().get(c.functionName());
        }
        List<IrSort> argNarrowings = new ArrayList<>(c.args().size());
        for (IrExpr arg : c.args()) {
            argNarrowings.add(infer(arg, ctx));
        }
        // Call-site type-parameter derivation (docs/type-parameters.md §3.1): for
        // a parametric function, unify its declared param sorts (the lens) against
        // the argument narrowings to bind its `[type E]` parameters, then
        // substitute into the return — so `idd(-5):E` resolves to `[Int:@==-5]`,
        // the precise result the caller observes. Single-overload only (a generic
        // function isn't overloaded); StaticDispatch handles everything else. The
        // inverse of substituteTypeVars: a one-directional match that binds.
        if (overloads.size() == 1 && !overloads.get(0).typeParams().isEmpty()) {
            IrStmt.FunctionDecl decl = overloads.get(0);
            Map<String, IrSort> bindings = new LinkedHashMap<>();
            List<IrParam> ps = decl.params();
            for (int i = 0; i < ps.size() && i < argNarrowings.size(); i++) {
                IrSort an = argNarrowings.get(i);
                if (an != null) {
                    unifyTypeArgs(ps.get(i).sort(), an, decl.typeParams().keySet(), bindings);
                }
            }
            return substituteTypeArgs(decl.returnSort(), bindings);
        }
        // Call-site return narrowing: an `assign proof` grants a return per region,
        // so if the argument narrowings land in one proof's region, its granted
        // return is what this call observes (a more precise result than the declared
        // base). Sound because the gate independently verifies every proof — a
        // narrowing only survives compilation if its proof discharged. Takes
        // precedence over the declared/dispatched return.
        IrSort granted = grantedReturnFor(c.functionName(), argNarrowings, ctx);
        if (granted != null) {
            return granted;
        }
        StaticDispatch.Result result = StaticDispatch.resolve(overloads, argNarrowings, ctx.sortRegistry());
        if (result instanceof StaticDispatch.Result.Resolved resolved) {
            return resolved.returnSort();
        }
        return ctx.functionReturns().get(c.functionName());
    }

    /**
     * One-directional match of a parameter sort (the lens, mentioning type
     * parameters) against an argument's narrowing, recording each parameter's
     * concrete sort into {@code out} (first occurrence wins — conflicts are the
     * construction gate's beat, not inference's). Bare {@code E} binds to the
     * whole narrowing; a parametric {@code Box[E]} unifies positionally against a
     * concrete {@code Box[Int]}.
     */
    private static void unifyTypeArgs(
            IrSort lens, IrSort concrete, Set<String> params, Map<String, IrSort> out) {
        if (!(lens instanceof IrSort.Named ln)) return;
        if (ln.typeArgs().isEmpty()) {
            if (params.contains(ln.name())) out.putIfAbsent(ln.name(), concrete);
            return;
        }
        if (concrete instanceof IrSort.Named cn
                && cn.name().equals(ln.name())
                && cn.typeArgs().size() == ln.typeArgs().size()) {
            for (int i = 0; i < ln.typeArgs().size(); i++) {
                unifyTypeArgs(ln.typeArgs().get(i), cn.typeArgs().get(i), params, out);
            }
        }
    }

    /** Substitutes the derived type-parameter bindings into a (return) sort. */
    private static IrSort substituteTypeArgs(IrSort sort, Map<String, IrSort> bindings) {
        if (bindings.isEmpty()) return sort;
        return switch (sort) {
            case IrSort.Named n -> {
                if (bindings.containsKey(n.name())) yield bindings.get(n.name());
                if (n.typeArgs().isEmpty()) yield n;
                yield new IrSort.Named(n.name(),
                        n.typeArgs().stream().map(a -> substituteTypeArgs(a, bindings)).toList(),
                        n.origin());
            }
            case IrSort.Union u -> new IrSort.Union(
                    u.branches().stream().map(b -> substituteTypeArgs(b, bindings)).toList(), u.origin());
            case IrSort.Intersection i -> new IrSort.Intersection(
                    i.branches().stream().map(b -> substituteTypeArgs(b, bindings)).toList(), i.origin());
            case IrSort.Method m -> new IrSort.Method(
                    m.paramSorts().stream().map(p -> substituteTypeArgs(p, bindings)).toList(),
                    substituteTypeArgs(m.returnSort(), bindings), m.origin());
            case IrSort.Dispatch d -> new IrSort.Dispatch(
                    d.keySorts().stream().map(k -> substituteTypeArgs(k, bindings)).toList(),
                    substituteTypeArgs(d.returnSort(), bindings), d.origin());
            // Refined's base is not a variable; Structural/Trait stay nominal.
            default -> sort;
        };
    }

    /** Probe body for the region pseudo-overloads — unused by StaticDispatch (it reads only params/return). */
    private static final IrExpr REGION_PROBE_BODY = IrExpr.lit(0);

    /**
     * The return granted by the {@code assign proof} whose region the argument
     * narrowings land in, or {@code null} when there are no proofs for the
     * function or the arguments don't definitely fall in exactly one region.
     * Each proof's {@code (params -> grantedReturn)} is treated as a pseudo-
     * overload and resolved by the same narrowing-match dispatch uses: a precise
     * argument lands in one region, an imprecise one matches none (residual) and
     * the caller falls back to the declared return.
     */
    private static IrSort grantedReturnFor(
            String functionName, List<IrSort> argNarrowings, InferenceContext ctx) {
        List<IrStmt.ReturnProof> proofs = ctx.returnProofs().get(functionName);
        if (proofs == null || proofs.isEmpty()) {
            return null;
        }
        List<IrStmt.FunctionDecl> regions = new ArrayList<>(proofs.size());
        for (IrStmt.ReturnProof p : proofs) {
            regions.add(new IrStmt.FunctionDecl(
                    functionName, p.params(), p.grantedReturn(), REGION_PROBE_BODY, p.origin()));
        }
        StaticDispatch.Result r = StaticDispatch.resolve(regions, argNarrowings, ctx.sortRegistry());
        return r instanceof StaticDispatch.Result.Resolved resolved ? resolved.returnSort() : null;
    }

    // --- Match -------------------------------------------------------------

    /**
     * Match: for each arm, derive the local hypothesis and infer the arm's
     * result under that hypothesis. Same-base union the arm results.
     *
     * <p>Hypothesis: when the scrutinee is an {@link IrExpr.Var} and the
     * pattern is {@link IrSort.Refined} or {@link IrSort.Structural}, the
     * var's env entry is replaced by the pattern for the arm's body
     * (mirrors {@link SortChecker}'s narrowing scope; Phase D refines to
     * intersect-with-prior). Non-Var scrutinees fall back to the outer
     * env.
     *
     * <p>If any arm returns {@code null}, the whole match returns
     * {@code null} — conservative: we don't widen by claiming "everything
     * but this arm is fine," we just don't narrow.
     */
    private static IrSort inferMatch(IrExpr.Match m, InferenceContext ctx) {
        List<IrSort> armResults = new ArrayList<>(m.branches().size());
        for (IrExpr.MatchBranch branch : m.branches()) {
            InferenceContext armCtx = ctx;
            if (m.scrutinee() instanceof IrExpr.Var v
                    && (branch.pattern() instanceof IrSort.Refined
                        || branch.pattern() instanceof IrSort.Structural)) {
                armCtx = ctx.withVar(v.name(), branch.pattern());
            }
            IrSort armResult = infer(branch.result(), armCtx);
            if (armResult == null) return null;
            armResults.add(armResult);
        }
        return sameBaseUnion(armResults);
    }

    // --- Record literal narrowing (Phase C) --------------------------------

    /**
     * Record literal: for each member with an inferrable narrowing,
     * substitute {@code @} in the member's predicate with
     * {@code @.fieldName} and AND the resulting predicates into a
     * struct-refined sort.
     *
     * <p>Anonymous records (no typeName) return {@code null} — we have
     * no nominal target to refine. If no members have inferrable
     * narrowings, returns {@code null}.
     */
    private static IrSort inferRecord(IrExpr.Record r, InferenceContext ctx) {
        if (r.typeName() == null) return null;
        // A native constructor's value is its carrier scalar — field-predicate
        // narrowings (@.unscaled==25) would claim projections that don't exist
        // until the bijection's other half lands. Bare nominal sort only.
        if (NativeConstructors.has(r.typeName())) return IrSort.named(r.typeName());
        List<IrExpr> conjuncts = new ArrayList<>();
        for (Map.Entry<String, IrExpr> entry : r.members().entrySet()) {
            IrSort memberNarrowing = infer(entry.getValue(), ctx);
            if (!(memberNarrowing instanceof IrSort.Refined refined)) continue;
            conjuncts.add(substituteSelfWithFieldAccess(refined.predicate(), entry.getKey()));
        }
        if (conjuncts.isEmpty()) return null;
        return new IrSort.Refined(r.typeName(), conjunctAnd(conjuncts), Origin.NONE);
    }

    // --- Field-access narrowing (Phase C) ----------------------------------

    /**
     * Field access on a narrowed record: extract conjuncts from the
     * base's narrowing that reference only {@code @.fieldName}, substitute
     * {@code @.fieldName → @}, and return as a refinement over the
     * field's declared base sort.
     *
     * <p>Cross-field conjuncts (e.g., {@code @.x + @.y > 0}) cannot be
     * decomposed into per-field constraints and are skipped. Bare
     * {@code @} (referring to the whole record) likewise disqualifies a
     * conjunct.
     *
     * <p>Requires the base's struct to be declared in
     * {@code ctx.structDefs} — without it, we can't determine the
     * field's base sort name. Returns {@code null} otherwise.
     */
    private static IrSort inferFieldAccess(IrExpr.FieldAccess fa, InferenceContext ctx) {
        IrSort baseNarrowing = infer(fa.base(), ctx);
        if (!(baseNarrowing instanceof IrSort.Refined refinedBase)) return null;

        IrSort.Structural struct = ctx.structDefs().get(refinedBase.name());
        if (struct == null) return null;
        IrSort fieldDeclared = struct.members().get(fa.fieldName());
        if (fieldDeclared == null) return null;
        String fieldBase = baseName(fieldDeclared);
        if (fieldBase == null) return null;

        List<IrExpr> matched = new ArrayList<>();
        collectFieldConjuncts(refinedBase.predicate(), fa.fieldName(), matched);
        if (matched.isEmpty()) return null;

        return new IrSort.Refined(fieldBase, conjunctAnd(matched), Origin.NONE);
    }

    /**
     * Walks an AND-tree of conjuncts; each leaf that references only
     * {@code @.fieldName} (and not bare {@code @} or any other
     * {@code @.field}) is substituted ({@code @.fieldName → @}) and added
     * to {@code out}.
     */
    private static void collectFieldConjuncts(
            IrExpr predicate, String fieldName, List<IrExpr> out) {
        if (predicate instanceof IrExpr.BinOp op && op.op() == IrExpr.Op.AND) {
            collectFieldConjuncts(op.left(), fieldName, out);
            collectFieldConjuncts(op.right(), fieldName, out);
            return;
        }
        if (selfAccessesAreOnlyField(predicate, fieldName)) {
            out.add(substituteFieldAccessWithSelf(predicate, fieldName));
        }
    }

    /**
     * True iff every {@link IrExpr.SelfRef} occurrence in {@code expr} is
     * wrapped in {@code FieldAccess(SelfRef, targetField)}. Bare Self or
     * a different {@code @.otherField} disqualifies. Pure leaves
     * (literals, vars) trivially qualify.
     */
    private static boolean selfAccessesAreOnlyField(IrExpr expr, String targetField) {
        return switch (expr) {
            case IrExpr.SelfRef ignored -> false;
            case IrExpr.FieldAccess fa -> {
                if (fa.base() instanceof IrExpr.SelfRef) {
                    yield fa.fieldName().equals(targetField);
                }
                yield selfAccessesAreOnlyField(fa.base(), targetField);
            }
            case IrExpr.BinOp op ->
                    selfAccessesAreOnlyField(op.left(), targetField)
                    && selfAccessesAreOnlyField(op.right(), targetField);
            case IrExpr.LetIn l ->
                    selfAccessesAreOnlyField(l.value(), targetField)
                    && selfAccessesAreOnlyField(l.body(), targetField);
            case IrExpr.Call c ->
                    c.args().stream().allMatch(a -> selfAccessesAreOnlyField(a, targetField));
            case IrExpr.Apply a ->
                    selfAccessesAreOnlyField(a.fn(), targetField)
                    && a.args().stream().allMatch(arg -> selfAccessesAreOnlyField(arg, targetField));
            case IrExpr.Lambda lam -> selfAccessesAreOnlyField(lam.body(), targetField);
            case IrExpr.Match m ->
                    selfAccessesAreOnlyField(m.scrutinee(), targetField)
                    && m.branches().stream()
                            .allMatch(b -> selfAccessesAreOnlyField(b.result(), targetField));
            case IrExpr.Record r ->
                    r.members().values().stream()
                            .allMatch(v -> selfAccessesAreOnlyField(v, targetField));
            case IrExpr.MethodCall mc ->
                    selfAccessesAreOnlyField(mc.receiver(), targetField)
                    && mc.args().stream().allMatch(a -> selfAccessesAreOnlyField(a, targetField));
            case IrExpr.Lit ignored -> true;
            case IrExpr.Dec ignored -> true;
            case IrExpr.Chr ignored -> true;
            case IrExpr.Str ignored -> true;
            case IrExpr.Bool ignored -> true;
            case IrExpr.Var ignored -> true;
            case IrExpr.DispatchRef ignored -> true;
            case IrExpr.Iterate ignored -> false;  // REVISIT (docs/iteration.md §10)
        };
    }

    /**
     * Replaces {@code FieldAccess(SelfRef, targetField)} with bare
     * {@code SelfRef} throughout {@code expr}. Used after
     * {@link #selfAccessesAreOnlyField} confirms safety.
     */
    private static IrExpr substituteFieldAccessWithSelf(IrExpr expr, String targetField) {
        return switch (expr) {
            case IrExpr.FieldAccess fa -> {
                if (fa.base() instanceof IrExpr.SelfRef
                        && fa.fieldName().equals(targetField)) {
                    yield IrExpr.self();
                }
                yield new IrExpr.FieldAccess(
                        substituteFieldAccessWithSelf(fa.base(), targetField),
                        fa.fieldName(),
                        fa.origin());
            }
            case IrExpr.BinOp op -> new IrExpr.BinOp(
                    op.op(),
                    substituteFieldAccessWithSelf(op.left(), targetField),
                    substituteFieldAccessWithSelf(op.right(), targetField),
                    op.origin());
            case IrExpr.LetIn l -> new IrExpr.LetIn(
                    l.name(), l.declaredSort(),
                    substituteFieldAccessWithSelf(l.value(), targetField),
                    substituteFieldAccessWithSelf(l.body(), targetField),
                    l.origin(), l.claim());
            case IrExpr.Call c -> {
                List<IrExpr> newArgs = new ArrayList<>(c.args().size());
                for (IrExpr a : c.args()) {
                    newArgs.add(substituteFieldAccessWithSelf(a, targetField));
                }
                yield new IrExpr.Call(c.functionName(), newArgs, c.origin());
            }
            case IrExpr.Apply a -> {
                List<IrExpr> newArgs = new ArrayList<>(a.args().size());
                for (IrExpr arg : a.args()) {
                    newArgs.add(substituteFieldAccessWithSelf(arg, targetField));
                }
                yield new IrExpr.Apply(
                        substituteFieldAccessWithSelf(a.fn(), targetField), newArgs, a.origin());
            }
            case IrExpr.Lambda lam -> new IrExpr.Lambda(
                    lam.params(), lam.returnSort(),
                    substituteFieldAccessWithSelf(lam.body(), targetField),
                    lam.origin());
            case IrExpr.Match m -> {
                List<IrExpr.MatchBranch> newBranches = new ArrayList<>(m.branches().size());
                for (IrExpr.MatchBranch b : m.branches()) {
                    newBranches.add(new IrExpr.MatchBranch(
                            b.pattern(),
                            substituteFieldAccessWithSelf(b.result(), targetField)));
                }
                yield new IrExpr.Match(
                        substituteFieldAccessWithSelf(m.scrutinee(), targetField),
                        newBranches, m.origin());
            }
            case IrExpr.Record r -> {
                Map<String, IrExpr> newMembers = new LinkedHashMap<>();
                for (Map.Entry<String, IrExpr> e : r.members().entrySet()) {
                    newMembers.put(e.getKey(),
                            substituteFieldAccessWithSelf(e.getValue(), targetField));
                }
                yield new IrExpr.Record(r.typeName(), newMembers, r.origin());
            }
            case IrExpr.MethodCall mc -> {
                List<IrExpr> newArgs = new ArrayList<>(mc.args().size());
                for (IrExpr arg : mc.args()) {
                    newArgs.add(substituteFieldAccessWithSelf(arg, targetField));
                }
                yield new IrExpr.MethodCall(
                        substituteFieldAccessWithSelf(mc.receiver(), targetField),
                        mc.methodName(), newArgs, mc.origin());
            }
            case IrExpr.Lit l -> l;
            case IrExpr.Dec d -> d;
            case IrExpr.Chr c -> c;
            case IrExpr.Str s -> s;
            case IrExpr.Bool b -> b;
            case IrExpr.Var v -> v;
            case IrExpr.SelfRef s -> s;
            case IrExpr.DispatchRef d -> d;
            case IrExpr.Iterate it -> it;  // REVISIT (docs/iteration.md §10)
        };
    }

    /**
     * Replaces {@code SelfRef} with {@code FieldAccess(SelfRef, fieldName)}
     * throughout {@code expr}. Inverse of
     * {@link #substituteFieldAccessWithSelf}, used by record-literal
     * narrowing to lift a member's predicate (over {@code @}) into a
     * record-level predicate (over {@code @.fieldName}).
     */
    private static IrExpr substituteSelfWithFieldAccess(IrExpr expr, String fieldName) {
        return switch (expr) {
            case IrExpr.SelfRef ignored ->
                    new IrExpr.FieldAccess(IrExpr.self(), fieldName, Origin.NONE);
            case IrExpr.FieldAccess fa -> new IrExpr.FieldAccess(
                    substituteSelfWithFieldAccess(fa.base(), fieldName),
                    fa.fieldName(),
                    fa.origin());
            case IrExpr.BinOp op -> new IrExpr.BinOp(
                    op.op(),
                    substituteSelfWithFieldAccess(op.left(), fieldName),
                    substituteSelfWithFieldAccess(op.right(), fieldName),
                    op.origin());
            case IrExpr.LetIn l -> new IrExpr.LetIn(
                    l.name(), l.declaredSort(),
                    substituteSelfWithFieldAccess(l.value(), fieldName),
                    substituteSelfWithFieldAccess(l.body(), fieldName),
                    l.origin(), l.claim());
            case IrExpr.Call c -> {
                List<IrExpr> newArgs = new ArrayList<>(c.args().size());
                for (IrExpr a : c.args()) {
                    newArgs.add(substituteSelfWithFieldAccess(a, fieldName));
                }
                yield new IrExpr.Call(c.functionName(), newArgs, c.origin());
            }
            case IrExpr.Apply a -> {
                List<IrExpr> newArgs = new ArrayList<>(a.args().size());
                for (IrExpr arg : a.args()) {
                    newArgs.add(substituteSelfWithFieldAccess(arg, fieldName));
                }
                yield new IrExpr.Apply(
                        substituteSelfWithFieldAccess(a.fn(), fieldName), newArgs, a.origin());
            }
            case IrExpr.MethodCall mc -> {
                List<IrExpr> newArgs = new ArrayList<>(mc.args().size());
                for (IrExpr arg : mc.args()) {
                    newArgs.add(substituteSelfWithFieldAccess(arg, fieldName));
                }
                yield new IrExpr.MethodCall(
                        substituteSelfWithFieldAccess(mc.receiver(), fieldName),
                        mc.methodName(), newArgs, mc.origin());
            }
            case IrExpr.Lambda lam -> new IrExpr.Lambda(
                    lam.params(), lam.returnSort(),
                    substituteSelfWithFieldAccess(lam.body(), fieldName),
                    lam.origin());
            case IrExpr.Match m -> {
                List<IrExpr.MatchBranch> newBranches = new ArrayList<>(m.branches().size());
                for (IrExpr.MatchBranch b : m.branches()) {
                    newBranches.add(new IrExpr.MatchBranch(
                            b.pattern(),
                            substituteSelfWithFieldAccess(b.result(), fieldName)));
                }
                yield new IrExpr.Match(
                        substituteSelfWithFieldAccess(m.scrutinee(), fieldName),
                        newBranches, m.origin());
            }
            case IrExpr.Record r -> {
                Map<String, IrExpr> newMembers = new LinkedHashMap<>();
                for (Map.Entry<String, IrExpr> e : r.members().entrySet()) {
                    newMembers.put(e.getKey(),
                            substituteSelfWithFieldAccess(e.getValue(), fieldName));
                }
                yield new IrExpr.Record(r.typeName(), newMembers, r.origin());
            }
            case IrExpr.Lit l -> l;
            case IrExpr.Dec d -> d;
            case IrExpr.Chr c -> c;
            case IrExpr.Str s -> s;
            case IrExpr.Bool b -> b;
            case IrExpr.Var v -> v;
            case IrExpr.DispatchRef d -> d;
            case IrExpr.Iterate it -> it;  // REVISIT (docs/iteration.md §10)
        };
    }

    // --- Arithmetic narrowing (linear bounds) ------------------------------

    /**
     * Integer-arithmetic BinOp ({@code + - *}): bound the expression with
     * {@link BoundAnalysis} under the env's refinements, then lift the
     * resulting {@link Interval} to an {@code [Int:…]} refinement. Non-
     * arithmetic ops (comparisons, boolean) yield {@code Bool}, not a
     * bounded {@code Int}, so they fall back to the declared sort.
     *
     * <p>{@code infer} never throws — a {@link CompileException} while
     * lowering to {@link SymExpr} degrades to {@code null} (declared sort).
     */
    private static IrSort inferBinOp(IrExpr.BinOp op, InferenceContext ctx) {
        if (!isArithmetic(op.op())) return null;
        SymExpr expr;
        List<SymExpr> hypotheses;
        try {
            expr = IrCompiler.compileSymExpr(op);
            hypotheses = hypothesesFromEnv(ctx);
        } catch (CompileException unused) {
            return null;
        }
        return intervalToIntSort(BoundAnalysis.bound(expr, hypotheses));
    }

    private static boolean isArithmetic(IrExpr.Op op) {
        return op == IrExpr.Op.ADD || op == IrExpr.Op.SUB || op == IrExpr.Op.MUL;
    }

    /**
     * Turns the env's refined bindings into {@link BoundAnalysis}
     * hypotheses: a binding {@code x → [Int:@>=1]} becomes the fact
     * {@code x >= 1} (the refinement predicate with {@code @} bound to the
     * var). Non-refined bindings contribute nothing.
     */
    private static List<SymExpr> hypothesesFromEnv(InferenceContext ctx) throws CompileException {
        List<SymExpr> hypotheses = new ArrayList<>();
        for (Map.Entry<String, IrSort> binding : ctx.typeEnv().entrySet()) {
            if (binding.getValue() instanceof IrSort.Refined refined) {
                SymExpr predicate = IrCompiler.compileSymExpr(refined.predicate());
                hypotheses.add(Substitute.applySelf(predicate, SymExpr.var(binding.getKey())));
            }
        }
        return hypotheses;
    }

    /**
     * Lifts a bounded {@link Interval} to an {@code [Int:…]} refinement:
     * a point {@code [k,k]} → {@code @==k}, a half-line → {@code @>=lo} or
     * {@code @<=hi}, a finite range → {@code @>=lo & @<=hi}. The unbounded
     * interval (and the empty one — contradictory env) yield {@code null},
     * meaning "no narrowing beyond the declared sort."
     */
    private static IrSort intervalToIntSort(Interval iv) {
        if (iv.isEmpty()) return null;
        boolean loInf = iv.lo() == Interval.NEG_INF;
        boolean hiInf = iv.hi() == Interval.POS_INF;
        if (loInf && hiInf) return null;
        if (iv.lo() == iv.hi()) return intRefined(cmpSelf(IrExpr.Op.EQ, iv.lo()));
        if (loInf) return intRefined(cmpSelf(IrExpr.Op.LE, iv.hi()));
        if (hiInf) return intRefined(cmpSelf(IrExpr.Op.GE, iv.lo()));
        return intRefined(new IrExpr.BinOp(
                IrExpr.Op.AND,
                cmpSelf(IrExpr.Op.GE, iv.lo()),
                cmpSelf(IrExpr.Op.LE, iv.hi()),
                Origin.NONE));
    }

    /** {@code @ op n} as a refinement predicate. */
    private static IrExpr cmpSelf(IrExpr.Op op, long n) {
        return new IrExpr.BinOp(
                op, new IrExpr.SelfRef(Origin.NONE), new IrExpr.Lit(n, Origin.NONE), Origin.NONE);
    }

    private static IrSort.Refined intRefined(IrExpr predicate) {
        return new IrSort.Refined("Int", predicate, Origin.NONE);
    }

    // --- Sort-level union / AND helpers ------------------------------------

    /**
     * Unions sibling narrowings, normalizing same-base branches into a
     * single {@link IrSort.Refined} with an {@code OR}-joined predicate
     * (mirroring {@code AltParser.normalizeMultiBranch}). Cross-base or
     * non-normalizable branches return {@code null} for this slice — the
     * full {@link IrSort.Union} form isn't surfaced from inference yet.
     */
    private static IrSort sameBaseUnion(List<IrSort> sorts) {
        if (sorts.isEmpty()) return null;
        if (sorts.size() == 1) return sorts.get(0);

        String base = null;
        for (IrSort s : sorts) {
            String n = baseName(s);
            if (n == null) return null;
            if (base == null) base = n;
            else if (!base.equals(n)) return null;
        }

        IrExpr combined = null;
        for (IrSort s : sorts) {
            IrExpr pred = (s instanceof IrSort.Refined r)
                    ? r.predicate()
                    : new IrExpr.Bool(true, Origin.NONE);
            combined = combined == null
                    ? pred
                    : new IrExpr.BinOp(IrExpr.Op.OR, combined, pred, Origin.NONE);
        }
        return new IrSort.Refined(base, combined, Origin.NONE);
    }

    /** Left-folds a list of predicates into an AND-chain. */
    private static IrExpr conjunctAnd(List<IrExpr> conjuncts) {
        IrExpr combined = conjuncts.get(0);
        for (int i = 1; i < conjuncts.size(); i++) {
            combined = new IrExpr.BinOp(
                    IrExpr.Op.AND, combined, conjuncts.get(i), Origin.NONE);
        }
        return combined;
    }

    /** Base-name extractor for {@link #sameBaseUnion}'s same-base check. */
    private static String baseName(IrSort s) {
        return switch (s) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> r.name();
            default -> null;
        };
    }

    /** Synthesizes {@code [Int:@==n]} for an integer literal. */
    private static IrSort.Refined intSingleton(long n) {
        return new IrSort.Refined(
                "Int",
                new IrExpr.BinOp(
                        IrExpr.Op.EQ,
                        new IrExpr.SelfRef(Origin.NONE),
                        new IrExpr.Lit(n, Origin.NONE),
                        Origin.NONE),
                Origin.NONE);
    }

    /** Synthesizes {@code [Bool:@==b]} for a boolean literal. */
    private static IrSort.Refined boolSingleton(boolean b) {
        return new IrSort.Refined(
                "Bool",
                new IrExpr.BinOp(
                        IrExpr.Op.EQ,
                        new IrExpr.SelfRef(Origin.NONE),
                        new IrExpr.Bool(b, Origin.NONE),
                        Origin.NONE),
                Origin.NONE);
    }

    /** Synthesizes {@code [Decimal:@==v]} for a decimal literal (value known exactly). */
    private static IrSort.Refined decimalSingleton(java.math.BigDecimal v) {
        return new IrSort.Refined(
                "Decimal",
                new IrExpr.BinOp(
                        IrExpr.Op.EQ,
                        new IrExpr.SelfRef(Origin.NONE),
                        new IrExpr.Dec(v, Origin.NONE),
                        Origin.NONE),
                Origin.NONE);
    }
}
