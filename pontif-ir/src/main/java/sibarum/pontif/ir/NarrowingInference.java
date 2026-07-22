package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;
import sibarum.pontif.core.Origin.Span;
import sibarum.pontif.core.symbolic.Substitute;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.predicates.BoundAnalysis;
import sibarum.pontif.predicates.Interval;
import sibarum.pontif.types.DispatchQuery;
import sibarum.pontif.types.DispatchResult;
import sibarum.pontif.types.TypeSystem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
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
            // stays "_" at this level (shallow — candidates aren't consulted). When
            // the referent carries an `assign proof f:Algebraic` claim, the shape is
            // the trait-view intersection `[Dispatch & Algebraic]` — the algebraic
            // guarantee ridden into the sort, so `.ast` resolves off the Algebraic
            // branch (AlgebraicDispatch, roadmap §5) and a widen to bare `[Dispatch]`
            // stays free (some-branch is-a).
            case IrExpr.DispatchRef d -> dispatchRefSort(d, ctx);
            case IrExpr.Bool b -> boolSingleton(b.value());
            case IrExpr.Var v -> ctx.typeEnv().get(v.name());
            case IrExpr.LetIn let -> infer(let.body(), letBodyCtx(let, ctx));
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
            case IrExpr.Iterate it -> inferIterate(it, ctx);
            // emit is write-only; the statement's value (and narrowing) is its body's.
            case IrExpr.Emit em -> infer(em.body(), ctx);
            // A cast's narrowing is its declared target sort — the coercion's result.
            case IrExpr.Cast cast -> cast.targetSort();
        };
    }

    /**
     * The narrowed sort of a metareference {@code $f[keys]}: a dispatch-style
     * {@link IrSort.CallSig} stamped with its CONCRETE nominal — {@code AlgebraicDispatch}
     * when {@code f} carries an {@code assign proof f:Algebraic} claim, else the plain
     * {@code DispatchBase} (docs/dispatch-method-elimination.md E2). Both are dispatch-style
     * (seeded in {@link CallKinds}), so either still fits a {@code [Dispatch(…)]} param via
     * key-sort subsumption; only {@code AlgebraicDispatch is-a Algebraic}, so {@code .ast}
     * resolves off its {@code ast} attribute and {@code $inc[…].ast} (DispatchBase) is a
     * compile error. The runtime image is the matching {@code RecordValue} (see
     * {@link sibarum.pontif.core.types.Metaref}).
     */
    private static IrSort dispatchRefSort(IrExpr.DispatchRef d, InferenceContext ctx) {
        String typeName = ctx.algebraicFunctions().contains(d.functionName())
                ? sibarum.pontif.core.types.Metaref.ALGEBRAIC_DISPATCH
                : sibarum.pontif.core.types.Metaref.DISPATCH;
        return new IrSort.CallSig(typeName, d.keySorts(), IrSort.named("_"), d.origin());
    }

    /**
     * Convenience: infer the return narrowing of a function declaration.
     * Seeds {@code ctx} with each param bound to its declared sort.
     */
    public static IrSort inferFunctionReturn(IrStmt.FunctionDecl fd, InferenceContext ctx) {
        InferenceContext seeded = ctx;
        Set<String> paramNames = new HashSet<>();
        for (IrParam p : fd.params()) {
            seeded = seeded.withVar(p.name(), p.sort());
            paramNames.add(p.name());
        }
        // A return narrowing escapes to the caller, where the params don't exist —
        // close the body's (possibly param-referencing) value-pin over them, which
        // yields the variable-free bound the caller's graph can use as a hypothesis.
        return closeOver(infer(fd.body(), seeded), paramNames, seeded);
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
        DispatchResult result = TypeSystem.standard()
                .dispatch(DispatchQuery.forCall(call.functionName(), argNarrowings), ctx);
        if (!(result instanceof DispatchResult.Resolved resolved)) {
            return null;
        }
        IrSort inferred = inferFunctionReturn(resolved.target(), ctx);
        return inferred instanceof IrSort.Refined refined ? refined : null;
    }

    /**
     * Infers an argument's narrowing <em>for call-site discharge</em>, projecting an
     * {@code Int} value-pin over the in-scope variables it mentions to a
     * hypothesis-derived bound: {@code [Int:@==n-1]} under {@code n:[Int:@>0]}
     * becomes {@code [Int:@>=0]} (via {@link BoundAnalysis}, the same projection
     * {@link #closeOver} does at scope boundaries). This lets the call gate discharge
     * a decremented/recursive argument against a weaker parameter refinement —
     * {@code imply([Int:@>=0], [Int:@>=0])} passes, where the raw pin
     * {@code [Int:@==n-1]} only yielded residual because {@code imply} never saw the
     * hypothesis {@code n>0}.
     *
     * <p>Sound and monotonic: the bound is a sound over-approximation under the env's
     * facts, so it never proves a false fit; when no projection applies it falls back
     * to the raw narrowing. (Safe to feed the gate only because the gate's FAILED is
     * disjoint-based — a bounded range that overlaps a param is residual, not a false
     * reject; see {@code StaticDispatch.gateFit}.)
     */
    public static IrSort inferArg(IrExpr arg, InferenceContext ctx) {
        IrSort narrowing = inferThroughLets(arg, ctx, Set.of());
        if (narrowing == null) return null;
        IrSort closed = closeOver(narrowing, ctx.typeEnv().keySet(), ctx);
        return closed != null ? closed : narrowing;
    }

    /**
     * The narrowing of an expression, seeing <em>through</em> a reference to a top-level {@code let}
     * (a 0-arg function lowered from {@code let x = …}) to its value's own narrowing — the
     * <em>effective</em> sort James's routing/claim rules read. A top-level let bound to a
     * construction narrows to that concrete type: {@code let dog:Animal = Dog()} narrows to
     * {@code Dog}, so {@code bark(dog)} routes to {@code bark(d:Dog)} and {@code let back:Rect = b}
     * (with {@code b} a let holding a {@code Rect}) proves. Everything else — a parameter, a general
     * function/method call — keeps {@link #infer}'s declared answer: a param {@code a:Animal} is only
     * a "could-be" {@code Dog}, and a return {@code human.pet():Animal} likewise, so those stay
     * {@code Animal} (and their downcasts are rejected). Cycle-guarded by {@code resolving} so a
     * mutually-referential let chain (a malformed cycle) can't loop.
     */
    private static IrSort inferThroughLets(IrExpr expr, InferenceContext ctx, Set<String> resolving) {
        if (expr instanceof IrExpr.Call c && c.args().isEmpty()) {
            List<IrStmt.FunctionDecl> ovs = ctx.overloads().get(c.functionName());
            if (ovs != null && ovs.size() == 1) {
                IrStmt.FunctionDecl fd = ovs.get(0);
                if (fd.topLevelLet() && fd.params().isEmpty() && !resolving.contains(fd.name())) {
                    Set<String> next = new HashSet<>(resolving);
                    next.add(fd.name());
                    IrSort value = inferThroughLets(fd.body(), ctx, next);
                    if (value != null) return value;
                }
            }
        }
        return infer(expr, ctx);
    }

    /**
     * The <em>floor</em> layer: {@link #infer}'s narrowed sort when it has
     * one, else the coarse <em>base</em> sort (bare {@code Int}/{@code Bool}/
     * {@code Decimal}/{@code String}/struct). Where {@link #infer} returns
     * {@code null} (= "nothing tighter than declared"), validation and
     * match-totality consumers still need <em>a</em> sort — the base — so they
     * can decide field-existence and exhaustiveness. This wrapper supplies it
     * without touching {@code infer}'s null contract (load-bearing for the
     * narrowing consumers — {@code MethodOperatorResolver}/{@code ConstructionGate}/
     * the receipt {@code Drafter}).
     *
     * <p>The one divergence from {@link #infer}: an inline {@link IrSort.Structural}
     * field-access base (a struct not registered in {@code ctx.structDefs()}) is
     * resolved here by reading the base's own members — whereas {@code infer}
     * deliberately yields {@code null} for it (pinned by
     * {@code NarrowingInferenceTest.fieldAccess_returnsNullWithoutStructDef}).
     */
    public static IrSort inferFloor(IrExpr expr, InferenceContext ctx) {
        IrSort narrowed = infer(expr, ctx);
        if (narrowed != null) return narrowed;
        return floorOf(expr, ctx);
    }

    /**
     * The coarse base-sort fallback, mirroring the bare cases of the (now
     * deleted) {@code SortChecker.inferSort}. Only reached when {@link #infer}
     * yields {@code null}; recurses through {@link #inferFloor} so nested bases
     * (a field-access base, an arithmetic operand) also fall to their floor.
     */
    private static IrSort floorOf(IrExpr expr, InferenceContext ctx) {
        return switch (expr) {
            case IrExpr.Var v -> ctx.typeEnv().get(v.name());
            // A cast produces its named target sort regardless of source.
            case IrExpr.Cast cast -> cast.targetSort();
            case IrExpr.BinOp op -> switch (op.op()) {
                case ADD, SUB, MUL, DIV, MOD, POW -> floorArith(op, ctx);
                case LT, LE, GT, GE, EQ, NE, APPROX, AND, OR -> IrSort.named("Bool");
            };
            case IrExpr.FieldAccess fa -> {
                // Single-name struct resolution (no body recursion), so a
                // recursive type still terminates. Unlike infer's FieldAccess,
                // an inline Structural base resolves via its own members.
                IrSort.Structural sp = resolveNominal(inferFloor(fa.base(), ctx), ctx.structDefs());
                yield sp != null ? sp.members().get(fa.fieldName()) : null;
            }
            // A method call's result is the resolved method's declared return,
            // keyed on the receiver's base sort (`Type.method`). Best-effort floor
            // (infer abstains on MethodCall until MethodResolver runs) — same
            // resolve-by-sort-then-return mechanism as the operator case below,
            // just receiver-rooted (one operand → less dispatch ambiguity).
            case IrExpr.MethodCall mc -> {
                IrSort recv = inferFloor(mc.receiver(), ctx);
                String recvBase = recv == null ? null : baseName(recv);
                if (recvBase == null || recvBase.equals("_")) yield null;
                yield ctx.functionReturns().get(recvBase + "." + mc.methodName());
            }
            case IrExpr.Call c -> ctx.functionReturns().get(c.functionName());
            // A record's structural narrowing — the shape interchangeable with the
            // field-conjunct refinement infer produces for a NAMED record (same
            // semantics, different shape). Reached here only for the cases infer
            // abstains on (an anonymous record → "_record"); members fall to their
            // own floor ("_" when un-narrowable, never null in the map).
            case IrExpr.Record r -> {
                Map<String, IrSort> members = new LinkedHashMap<>();
                for (Map.Entry<String, IrExpr> e : r.members().entrySet()) {
                    IrSort m = inferFloor(e.getValue(), ctx);
                    members.put(e.getKey(), m != null ? m : IrSort.named("_"));
                }
                yield IrSort.structural(r.typeName() != null ? r.typeName() : "_record", members);
            }
            // A lambda's shape is its method sort (param sorts → return sort).
            case IrExpr.Lambda lam -> new IrSort.CallSig(IrSort.CallSig.METHOD,
                    lam.params().stream().map(IrParam::sort).toList(),
                    lam.returnSort(), lam.origin());
            // A metareference's floor IS its concrete dispatch nominal
            // (AlgebraicDispatch/DispatchBase) — the field-existence gate reads it to
            // decide whether `.ast` is available (docs/dispatch-method-elimination.md E2).
            case IrExpr.DispatchRef d -> dispatchRefSort(d, ctx);
            // Match / LetIn / Apply / SelfRef / Iterate: no coarser floor than infer gives.
            default -> null;
        };
    }

    /**
     * Floor for an arithmetic BinOp the {@code infer} pin couldn't type (an
     * un-routed user operator, or a non-kernel operand). String concat → String;
     * any Decimal operand → Decimal; a built-in {@code Int}-on-{@code Int} →
     * {@code Int}. Otherwise it's an un-routed user operator (no built-in for
     * DIV/MOD/POW, or a non-primitive operand): its result is the operator
     * overload's declared return, looked up by the operator <em>symbol</em> — the
     * SAME mechanism as a method call, keyed on the operator instead of a receiver
     * (more operands → more dispatch ambiguity, identical resolution). Post-link the
     * operator key is FQN'd so the bare-symbol lookup misses → null (no regression);
     * at parse time it hits the parser's bare-keyed declared returns.
     */
    private static IrSort floorArith(IrExpr.BinOp op, InferenceContext ctx) {
        IrSort ls = inferFloor(op.left(), ctx);
        IrSort rs = inferFloor(op.right(), ctx);
        String lb = ls == null ? null : baseName(ls);
        String rb = rs == null ? null : baseName(rs);
        if (op.op() == IrExpr.Op.ADD && ("String".equals(lb) || "String".equals(rb))) {
            return IrSort.named("String");
        }
        if ("Decimal".equals(lb) || "Decimal".equals(rb)) return IrSort.named("Decimal");
        boolean userOp = switch (op.op()) {
            case DIV, MOD, POW -> true;
            default -> isUserType(lb) || isUserType(rb);
        };
        if (userOp) {
            String sym = operatorTextFor(op.op());
            return sym == null ? null : ctx.functionReturns().get(sym);
        }
        if ("Int".equals(lb) && "Int".equals(rb)) return IrSort.named("Int");
        return null;
    }

    /** Whether an operand's narrowed base is a user type (struct), not a primitive. */
    private static boolean isUserTypeOperand(IrExpr e, InferenceContext ctx) {
        IrSort s = inferFloor(e, ctx);
        return isUserType(s == null ? null : baseName(s));
    }

    /** A non-primitive, known base name — a user struct/type operand. */
    private static boolean isUserType(String base) {
        return base != null && !base.equals("_")
                && !base.equals("Int") && !base.equals("Bool")
                && !base.equals("Decimal") && !base.equals("Char") && !base.equals("String");
    }

    /** The operator symbol used as an overload key (mirrors AltParser). */
    private static String operatorTextFor(IrExpr.Op op) {
        return switch (op) {
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*"; case DIV -> "/";
            case MOD -> "%"; case POW -> "^";
            case LT -> "<"; case LE -> "<="; case GT -> ">"; case GE -> ">=";
            case EQ -> "=="; case NE -> "!=";
            case APPROX, AND, OR -> null;
        };
    }

    /**
     * Resolves a sort to its struct definition: an inline {@link IrSort.Structural}
     * directly, a nominal {@link IrSort.Named}/{@link IrSort.Refined} by name via
     * {@code structDefs}. {@code null} when it isn't a struct. A single name
     * lookup (never recursing into the body) keeps consumers terminating on a
     * recursive type.
     */
    private static IrSort.Structural resolveNominal(
            IrSort sort, Map<String, IrSort.Structural> structDefs) {
        if (sort == null) return null;
        return switch (sort) {
            case IrSort.Structural s -> s;
            case IrSort.Named n -> structDefs.get(n.name());
            case IrSort.Refined r -> structDefs.get(r.name());
            default -> null;
        };
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
        DispatchResult result = TypeSystem.standard()
                .dispatch(DispatchQuery.forCall(c.functionName(), argNarrowings), ctx);
        if (result instanceof DispatchResult.Resolved resolved) {
            return resolved.target().returnSort();
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
            case IrSort.CallSig c -> new IrSort.CallSig(c.typeName(),
                    c.paramSorts().stream().map(p -> substituteTypeArgs(p, bindings)).toList(),
                    c.paramNames(), substituteTypeArgs(c.returnSort(), bindings), c.origin());
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
            IrSort armResult = infer(branch.result(), matchArmCtx(m, branch, ctx));
            if (armResult == null) return null;
            armResults.add(armResult);
        }
        return sameBaseUnion(armResults);
    }

    // --- Iteration construct (docs/iteration.md) ---------------------------

    /**
     * Iteration result narrowing — the map+filter logic. Each output stream's
     * element sort is the same-base union, across the arms writing to it, of
     * each written value's narrowing taken under the arm's pattern as the
     * <em>element hypothesis</em> ({@code element → arm.pattern()}, mirroring
     * {@link #inferMatch}). This is the element-quantified narrowing
     * (∀ element ⟹ stream-of-refined): a filter arm {@code [@>0]} writing the
     * element verbatim lifts to {@code Stream[Int:@>0]}; a map arm transforming
     * the element lifts the transformed narrowing.
     *
     * <p>The seal mirrors {@link IrInterpreter}'s {@code evalIterate}: a single
     * output (map; a one-stream filter) narrows to {@code Stream[T']} directly;
     * multiple outputs (filter's {@code accept}/{@code reject}) to an anonymous
     * {@code _record} keyed by output name. An output whose element sort can't
     * be derived narrows to a bare {@code Stream} (never {@code _} — the result
     * is a stream regardless).
     */
    private static IrSort inferIterate(IrExpr.Iterate it, InferenceContext ctx) {
        Map<String, List<IrSort>> writtenByOutput = new LinkedHashMap<>();
        for (IrExpr.OutputSpec os : it.outputs()) {
            writtenByOutput.put(os.name(), new ArrayList<>());
        }
        for (IrExpr.Arm arm : it.arms()) {
            InferenceContext armCtx = ctx.withVar(it.element(), arm.pattern());
            for (IrExpr.Write w : arm.writes()) {
                List<IrSort> bucket = writtenByOutput.get(w.output());
                // A write to an undeclared output is SortChecker's beat, not ours.
                // The element var leaves scope at stream quantification (∀ element ⟹
                // …), so close the written value's pin over it → the element-quantified
                // bound (e+1 with e:[@>=0] → [Int:@>=1]).
                if (bucket != null) {
                    bucket.add(closeOver(infer(w.value(), armCtx), Set.of(it.element()), armCtx));
                }
            }
        }
        Map<String, IrSort> streamSorts = new LinkedHashMap<>();
        for (IrExpr.OutputSpec os : it.outputs()) {
            // Slice 1: only STREAM outputs parse. A non-stream output (ACCUMULATOR
            // and friends — not yet reachable) has no stream element sort.
            IrSort elem = os.kind() == IrExpr.OutputKind.STREAM
                    ? unionOrNull(writtenByOutput.get(os.name()))
                    : null;
            streamSorts.put(os.name(), streamSort(elem));
        }
        if (streamSorts.size() == 1) {
            return streamSorts.values().iterator().next();
        }
        return IrSort.structural("_record", streamSorts);
    }

    /** {@code Stream[elem]}, or bare {@code Stream} when the element sort is unknown. */
    private static IrSort streamSort(IrSort elem) {
        return new IrSort.Named("Stream", elem == null ? List.of() : List.of(elem), Origin.NONE);
    }

    /**
     * Same-base union of the bucket, or {@code null} if it's empty, holds any
     * un-narrowable ({@code null}) entry, or spans bases. Guards {@code null}s
     * up front since {@link #sameBaseUnion} doesn't.
     */
    private static IrSort unionOrNull(List<IrSort> sorts) {
        if (sorts == null || sorts.isEmpty()) return null;
        for (IrSort s : sorts) {
            if (s == null) return null;
        }
        return sameBaseUnion(sorts);
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
     * narrowings, the narrowing floor is the bare struct type (the value
     * is at least a {@code TypeName}; roadmap §6.5).
     */
    private static IrSort inferRecord(IrExpr.Record r, InferenceContext ctx) {
        if (r.typeName() == null) return null;
        // A native constructor's value is its carrier scalar — field-predicate
        // narrowings (@.unscaled==25) would claim projections that don't exist
        // until the bijection's other half lands. Bare nominal sort only.
        if (NativeConstructors.has(r.typeName())) return IrSort.named(r.typeName());
        Map<String, IrSort> memberNarrowings = new LinkedHashMap<>();
        List<IrExpr> conjuncts = new ArrayList<>();
        for (Map.Entry<String, IrExpr> entry : r.members().entrySet()) {
            IrSort memberNarrowing = infer(entry.getValue(), ctx);
            memberNarrowings.put(entry.getKey(), memberNarrowing);
            if (memberNarrowing instanceof IrSort.Refined refined) {
                conjuncts.add(substituteSelfWithFieldAccess(refined.predicate(), entry.getKey()));
            }
        }
        // Carry the derived type-args (Box(5) → Box[Int]): the type-arg-aware Assignability (roadmap
        // §4.5 item 2) needs them to decide `let b:Box[Int] = Box(5)`. Empty for a non-parametric struct.
        List<IrSort> typeArgs = deriveConstructionTypeArgs(r.typeName(), memberNarrowings, ctx);
        // A construction's narrowing is at LEAST its own concrete struct type — the value IS a
        // `TypeName` (roadmap §6.5, the concrete identity), even when no field carries a refinement
        // (e.g. a String field). Only the extra field-predicate conjuncts are optional.
        if (conjuncts.isEmpty()) {
            return typeArgs.isEmpty() ? IrSort.named(r.typeName())
                    : new IrSort.Named(r.typeName(), typeArgs, Origin.NONE);
        }
        return new IrSort.Refined(r.typeName(), typeArgs, conjunctAnd(conjuncts), Origin.NONE);
    }

    /**
     * The type-arguments a parametric construction binds, base-reduced ({@code Box(5)} → {@code [Int]}):
     * reusing {@link #unifyTypeArgs} — "the field is the witness" — each field sort mentioning a
     * {@code type T} is matched against its argument's narrowing, and the bound narrowing is reduced to
     * its base type ({@code Int}, not the {@code [Int:@==5]} narrowing) so the invariant match
     * {@code Box[Int] == Box[Int]} holds; the field predicates stay in the refinement. Empty when the
     * struct is non-parametric / unregistered, or a parameter is left unbound (a partial derivation
     * would produce a malformed applied sort — the construction gate reports the real conflict/leak).
     */
    private static List<IrSort> deriveConstructionTypeArgs(
            String typeName, Map<String, IrSort> memberNarrowings, InferenceContext ctx) {
        IrSort.Structural struct = ctx.structDefs().get(typeName);
        if (struct == null || struct.typeParams().isEmpty()) return List.of();
        Set<String> params = struct.typeParams().keySet();
        Map<String, IrSort> bound = new LinkedHashMap<>();
        for (Map.Entry<String, IrSort> entry : memberNarrowings.entrySet()) {
            IrSort field = struct.members().get(entry.getKey());
            if (field != null && entry.getValue() != null) {
                unifyTypeArgs(field, entry.getValue(), params, bound);
            }
        }
        List<IrSort> args = new ArrayList<>(params.size());
        for (String p : params) {
            String base = bound.containsKey(p) ? baseName(bound.get(p)) : null;
            if (base == null) return List.of();   // unbound / non-nominal → don't stamp a partial sort
            args.add(IrSort.named(base));
        }
        return args;
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
        if (baseNarrowing == null) return null;

        // Intersection base: the some-branch member rule — the field's narrowing is
        // whatever the (unique) branch providing it projects. A bare `[A & B]` has
        // the members of A plus the members of B; cross-branch disagreement abstains
        // to null (the SortChecker field gate reports the ambiguity).
        if (baseNarrowing instanceof IrSort.Intersection inter) {
            IrSort found = null;
            for (IrSort branch : inter.branches()) {
                IrSort projected = inferFieldOnBase(branch, fa, ctx);
                if (projected == null) continue;
                if (found != null && !found.equals(projected)) return null;
                found = projected;
            }
            return found;
        }
        return inferFieldOnBase(baseNarrowing, fa, ctx);
    }

    /**
     * Projects {@code fa.fieldName} off a single (non-intersection) base narrowing,
     * or null when the base is not a struct we can project through. Split out of
     * {@link #inferFieldAccess} so the intersection arm can consult each branch.
     */
    private static IrSort inferFieldOnBase(
            IrSort baseNarrowing, IrExpr.FieldAccess fa, InferenceContext ctx) {
        // Refined base: project the field-specific refinement conjuncts, so a
        // narrowing on the whole struct (`[Point:@.x>0]`) flows to the field.
        if (baseNarrowing instanceof IrSort.Refined refinedBase) {
            IrSort.Structural struct = ctx.structDefs().get(refinedBase.name());
            if (struct == null) return null;
            IrSort fieldDeclared = struct.members().get(fa.fieldName());
            if (fieldDeclared == null) return null;
            String fieldBase = baseName(fieldDeclared);
            if (fieldBase == null) return null;

            List<IrExpr> matched = new ArrayList<>();
            collectFieldConjuncts(refinedBase.predicate(), fa.fieldName(), matched);
            // Only cross-field conjuncts (e.g. `@.x + @.y > 0`) — can't decompose
            // to a field-specific narrowing, so skip conservatively.
            if (matched.isEmpty()) return null;

            return new IrSort.Refined(fieldBase, conjunctAnd(matched), Origin.NONE);
        }

        // Plain struct base (a bare Named sort — e.g. a method's `this`, or any
        // struct-typed value): the field's narrowing is its declared sort. This is
        // what makes `this.field` fully typed (so `this.field.method()` resolves),
        // matching SortChecker.inferSort's FieldAccess projection. Projection goes
        // through the registered struct table by name (the authoritative field
        // sorts), per the Phase-C contract — an inline Structural without a
        // structDefs entry still yields null.
        // The base's nominal name. A metareference base narrows to a dispatch-style CallSig
        // (`$f[…]` → [Dispatch] / [AlgebraicDispatch]) whose nominal is its typeName, not a plain
        // Named — so read it explicitly here (the shared baseName helper stays Named/Refined-only,
        // its contract for the struct-field paths). Without this a metareference field access has no
        // nominal to project through and the attribute-producer lookup below never fires.
        String baseName = baseName(baseNarrowing);
        if (baseName == null && baseNarrowing instanceof IrSort.CallSig cs) {
            baseName = cs.typeName();
        }
        if (baseName == null) return null;
        IrSort.Structural struct = ctx.structDefs().get(baseName);
        if (struct != null) {
            IrSort field = struct.members().get(fa.fieldName());
            if (field != null) return field;
        }
        // Not a struct field. Try a trait ATTRIBUTE PRODUCER on this nominal — e.g. `.ast` on the
        // metareference nominal AlgebraicDispatch, whose `ast` producer is declared in the Algebraic
        // trait. Its return sort is captured in functionReturns keyed `<owner>.<attr>` (owner maybe
        // module-qualified). Projecting it makes a union-typed member (`ast:[Const|…|Log]`) STATICALLY
        // known so `let e:AlgExpr = $f[…].ast` proves against the union at the construction gate.
        String attrSuffix = "." + fa.fieldName();
        for (Map.Entry<String, IrSort> e : ctx.functionReturns().entrySet()) {
            String key = e.getKey();
            if (!key.endsWith(attrSuffix)) continue;
            String owner = key.substring(0, key.length() - attrSuffix.length());
            if (owner.equals(baseName) || owner.endsWith("/" + baseName) || owner.endsWith("." + baseName)) {
                return e.getValue();
            }
        }
        return null;
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
            // An iteration is a stream/tuple value, never a predicate term
            // (IrCompiler.compileSymExpr rejects one inside a refinement), so
            // this @-walk never reaches it; disqualify conservatively.
            case IrExpr.Iterate ignored -> false;
            // Unreachable inside a predicate (emit can't appear there); disqualify.
            case IrExpr.Emit ignored -> false;
            // Unreachable inside a predicate (casts are forbidden there), but
            // the cast value can only qualify if it does — recurse.
            case IrExpr.Cast cast -> selfAccessesAreOnlyField(cast.value(), targetField);
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
            // Unreachable in practice: an iteration can't appear inside a
            // refinement predicate (IrCompiler.compileSymExpr forbids it), and
            // these helpers only ever walk predicates — nothing to substitute.
            case IrExpr.Iterate it -> it;
            case IrExpr.Emit em -> em;
            case IrExpr.Cast cast -> new IrExpr.Cast(cast.targetSort(),
                    substituteFieldAccessWithSelf(cast.value(), targetField), cast.origin());
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
            // Unreachable in practice: an iteration can't appear inside a
            // refinement predicate (IrCompiler.compileSymExpr forbids it), and
            // these helpers only ever walk predicates — nothing to substitute.
            case IrExpr.Iterate it -> it;
            case IrExpr.Emit em -> em;
            case IrExpr.Cast cast -> new IrExpr.Cast(cast.targetSort(),
                    substituteSelfWithFieldAccess(cast.value(), fieldName), cast.origin());
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
        // Bool-valued ops (comparisons, logical) pin to the exact [Bool:@==expr]
        // — the narrowest kernel-compilable narrowing, valid wherever the operand
        // vars are in scope (closed at boundaries by closeOver, like the Int pin).
        if (isBoolValued(op.op())) {
            if (!kernelCompilable(op)) return null;
            return new IrSort.Refined("Bool",
                    new IrExpr.BinOp(IrExpr.Op.EQ, new IrExpr.SelfRef(op.origin()), op, op.origin()),
                    op.origin());
        }
        if (!isArithmetic(op.op())) return null;
        // Decimal arithmetic carries no value-level narrowing (the kernel is
        // integer-only) — bare Decimal, matching the parser's maximal shape.
        if (isDecimalOperand(op.left(), ctx) || isDecimalOperand(op.right(), ctx)) {
            return IrSort.named("Decimal");
        }
        // A user-type operand means this is a USER operator (e.g. +(Vec,Vec):Vec),
        // not Int arithmetic — its result is the overload's return, NOT an Int pin.
        // (compileSymExpr treats a struct/constructor operand as an opaque atom, so
        // it wouldn't catch this — the old parser's isUserStructOperand guard did.)
        // Abstain so the floor's operator-return lookup (floorArith) supplies it.
        if (isUserTypeOperand(op.left(), ctx) || isUserTypeOperand(op.right(), ctx)) {
            return null;
        }
        // The exact value-pin `[Int:@==expr]` is the narrowest kernel-compilable
        // predicate, valid wherever expr's free variables are in scope (the
        // CONSUMING scope — closing over variables that leave scope is a boundary
        // concern, see #closeOver, not done here). Emit it only when the predicate
        // compiles — the one core invariant (never mint a non-compilable
        // refinement; isArithmetic already excludes DIV/MOD/POW, and a struct /
        // non-kernel operand isn't kernel-compilable). See docs/inference-unification.md.
        if (!kernelCompilable(op)) return null;
        return intRefined(new IrExpr.BinOp(
                IrExpr.Op.EQ, new IrExpr.SelfRef(op.origin()), op, op.origin()));
    }

    /**
     * Whether {@code expr} lowers to the linear refinement kernel. {@code infer}
     * runs at PARSE time too (via the parser's maximal-sort typing), where an
     * expression can still hold an unresolved {@link IrExpr.MethodCall} or an
     * un-routed operator — {@link IrCompiler#compileSymExpr} signals those with a
     * {@link CompileException} (non-kernel form) OR an {@link IllegalStateException}
     * (the unresolved-MethodCall invariant breach). Either means "not a kernel
     * term": abstain, never throw (infer's contract).
     */
    private static boolean kernelCompilable(IrExpr expr) {
        try {
            IrCompiler.compileSymExpr(expr);
            return true;
        } catch (CompileException | RuntimeException unused) {
            return false;
        }
    }

    private static boolean isArithmetic(IrExpr.Op op) {
        return op == IrExpr.Op.ADD || op == IrExpr.Op.SUB || op == IrExpr.Op.MUL;
    }

    private static boolean isBoolValued(IrExpr.Op op) {
        return switch (op) {
            case LT, LE, GT, GE, EQ, NE, APPROX, AND, OR -> true;
            case ADD, SUB, MUL, DIV, MOD, POW -> false;
        };
    }

    /** Whether an operand's value is Decimal-sorted (any Decimal operand keeps the result Decimal). */
    private static boolean isDecimalOperand(IrExpr e, InferenceContext ctx) {
        return switch (e) {
            case IrExpr.Dec ignored -> true;
            case IrExpr.Var v -> "Decimal".equals(nullableBaseName(ctx.typeEnv().get(v.name())));
            case IrExpr.BinOp op -> isDecimalOperand(op.left(), ctx) || isDecimalOperand(op.right(), ctx);
            case IrExpr.Cast c -> "Decimal".equals(nullableBaseName(c.targetSort()));
            default -> false;
        };
    }

    private static String nullableBaseName(IrSort s) {
        return s == null ? null : baseName(s);
    }

    /**
     * The <em>effective</em> (accumulated) sort of an expression at its use site — the
     * Inferred record of {@code docs/type-records.md}. It is {@link #infer}'s narrowing with
     * every in-scope variable projected out to a variable-free bound under the env's
     * hypotheses: the same projection {@link #closeOver} performs at scope boundaries, applied
     * here at <em>every</em> position, so a consumer (a construction/claim gate, or an IDE
     * hover) reads the fully accumulated sort rather than a raw pin. {@code n - 1} under
     * {@code n:[Int:@>0]} is {@code [Int:@>=0]}, not {@code [Int:@==n-1]}. Falls back to the
     * bare base when the projection is unbounded (the pin cannot survive without its free
     * variables); returns {@code null} exactly when {@link #infer} does.
     */
    public static IrSort effectiveSort(IrExpr expr, InferenceContext ctx) {
        // See through a top-level-let reference to its value's narrowing (the effective sort) —
        // the same move inferArg makes for the call gate, shared so both gates read one answer.
        IrSort narrowing = inferThroughLets(expr, ctx, Set.of());
        if (narrowing == null) return null;
        // closeOver recurses over the narrowing's predicate; run at every position, a pathologically
        // deep predicate (a long operator chain in generated code) would risk overflowing. Cap it —
        // too deep → skip the projection and report the bare base, a sound coarser effective sort.
        boolean projectable = !(narrowing instanceof IrSort.Refined ref)
                || predicateDepthWithin(ref.predicate(), MAX_PROJECTION_DEPTH);
        if (projectable) {
            IrSort projected = closeOver(narrowing, ctx.typeEnv().keySet(), ctx);
            if (projected != null) return projected;
        }
        // Unbounded (or un-projectably-deep): a pin still mentioning free vars would leak them into
        // the consuming scope, so the honest effective sort is the bare base.
        String base = baseName(narrowing);
        return base != null ? IrSort.named(base) : narrowing;
    }

    private static final int MAX_PROJECTION_DEPTH = 200;

    /** Whether {@code predicate}'s nesting depth is within {@code max} — an <em>iterative</em> check
     *  (explicit stack, no recursion) so the guard itself never overflows on the very predicates it
     *  is meant to fence off. Early-exits as soon as the bound is exceeded. */
    private static boolean predicateDepthWithin(IrExpr predicate, int max) {
        Deque<IrExpr> nodes = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        nodes.push(predicate);
        depths.push(1);
        while (!nodes.isEmpty()) {
            IrExpr n = nodes.pop();
            int d = depths.pop();
            if (d > max) return false;
            switch (n) {
                case IrExpr.BinOp op -> {
                    nodes.push(op.left());  depths.push(d + 1);
                    nodes.push(op.right()); depths.push(d + 1);
                }
                case IrExpr.FieldAccess fa -> { nodes.push(fa.base()); depths.push(d + 1); }
                case IrExpr.Call c -> {
                    for (IrExpr a : c.args()) { nodes.push(a); depths.push(d + 1); }
                }
                case IrExpr.Cast c -> { nodes.push(c.value()); depths.push(d + 1); }
                default -> { }
            }
        }
        return true;
    }

    // --- Shared env-threading (one definition, reused by infer and the lens walk) -------------------

    /** The context inside a let body: the bound name narrowed to the value's inferred sort (or the
     *  declared sort when inference abstains). The one definition of the let-narrowing decision. */
    private static InferenceContext letBodyCtx(IrExpr.LetIn let, InferenceContext ctx) {
        IrSort value = infer(let.value(), ctx);
        return ctx.withVar(let.name(), value != null ? value : let.declaredSort());
    }

    /** The context inside a match arm: a variable scrutinee is narrowed to the arm's pattern when
     *  that pattern is a refinement or struct. The one definition of the arm-narrowing decision. */
    private static InferenceContext matchArmCtx(
            IrExpr.Match m, IrExpr.MatchBranch branch, InferenceContext ctx) {
        if (m.scrutinee() instanceof IrExpr.Var v
                && (branch.pattern() instanceof IrSort.Refined
                    || branch.pattern() instanceof IrSort.Structural)) {
            return ctx.withVar(v.name(), branch.pattern());
        }
        return ctx;
    }

    /** The context inside a lambda body: each parameter bound to its declared sort. */
    private static InferenceContext lambdaBodyCtx(IrExpr.Lambda lam, InferenceContext ctx) {
        InferenceContext c = ctx;
        for (IrParam p : lam.params()) {
            if (p.sort() != null) c = c.withVar(p.name(), p.sort());
        }
        return c;
    }

    // --- The effective-sort lens (span → effective sort, every position) ----------------------------

    /**
     * The effective-sort lens over an expression tree: {@code span → effective sort} at every
     * position that carries one, for the construction/claim gates and (later) an IDE. Reuses
     * {@link #effectiveSort} for the per-node calculation and the shared env-threading helpers
     * ({@link #letBodyCtx} / {@link #matchArmCtx} / {@link #lambdaBodyCtx}) for the domain-specific
     * scoping — only the generic child-walk is local (two cohesive passes over one tree is not
     * duplication). Positions with no source span (synthesized nodes) are omitted.
     */
    public static Map<Span, IrSort> effectiveSorts(IrExpr root, InferenceContext ctx) {
        Map<Span, IrSort> lens = new LinkedHashMap<>();
        collectEffectiveSorts(root, ctx, lens);
        return lens;
    }

    private static void collectEffectiveSorts(IrExpr e, InferenceContext ctx, Map<Span, IrSort> lens) {
        if (e == null) return;
        IrSort effective = effectiveSort(e, ctx);
        if (effective != null && e.origin() != null && e.origin().span() != null) {
            lens.put(e.origin().span(), effective);
        }
        switch (e) {
            case IrExpr.LetIn let -> {
                collectEffectiveSorts(let.value(), ctx, lens);
                collectEffectiveSorts(let.body(), letBodyCtx(let, ctx), lens);
            }
            case IrExpr.Match m -> {
                collectEffectiveSorts(m.scrutinee(), ctx, lens);
                for (IrExpr.MatchBranch b : m.branches()) {
                    collectEffectiveSorts(b.result(), matchArmCtx(m, b, ctx), lens);
                }
            }
            case IrExpr.BinOp op -> {
                collectEffectiveSorts(op.left(), ctx, lens);
                collectEffectiveSorts(op.right(), ctx, lens);
            }
            case IrExpr.FieldAccess fa -> collectEffectiveSorts(fa.base(), ctx, lens);
            case IrExpr.Call c -> {
                for (IrExpr a : c.args()) collectEffectiveSorts(a, ctx, lens);
            }
            case IrExpr.Record r -> {
                for (IrExpr v : r.members().values()) collectEffectiveSorts(v, ctx, lens);
            }
            case IrExpr.Apply ap -> {
                collectEffectiveSorts(ap.fn(), ctx, lens);
                for (IrExpr a : ap.args()) collectEffectiveSorts(a, ctx, lens);
            }
            case IrExpr.Lambda lam -> collectEffectiveSorts(lam.body(), lambdaBodyCtx(lam, ctx), lens);
            case IrExpr.Emit em -> {
                collectEffectiveSorts(em.event(), ctx, lens);
                collectEffectiveSorts(em.body(), ctx, lens);
            }
            case IrExpr.Cast cast -> collectEffectiveSorts(cast.value(), ctx, lens);
            // Leaves and not-yet-walked (Iterate): the node's own sort is recorded above.
            default -> { }
        }
    }

    /**
     * Closes a narrowing over variables that are leaving the consuming scope: any
     * value-pin / predicate referencing an {@code escaping} variable is re-projected
     * to a variable-free numeric bound via {@link BoundAnalysis} (a bound IS a pin
     * with its free variables eliminated). A narrowing free of the escaping vars is
     * returned unchanged. Called at scope boundaries — function return, stream-element
     * quantification (see docs/inference-unification.md). Returns {@code null} when the
     * projection is unbounded (nothing tighter than the declared sort survives the
     * boundary).
     */
    static IrSort closeOver(IrSort narrowing, Set<String> escaping, InferenceContext ctx) {
        if (!(narrowing instanceof IrSort.Refined refined)) return narrowing;
        // No escaping variable in the predicate → already closed; keep it verbatim.
        if (!predicateMentionsAny(refined.predicate(), escaping)) return narrowing;
        // The narrowing references a departing variable and must be projected out.
        // An Int value-pin `@==expr` closes by bounding `expr` under the env's
        // hypotheses (the bound IS the pin with its free variables eliminated).
        if ("Int".equals(refined.name())
                && refined.predicate() instanceof IrExpr.BinOp eq
                && eq.op() == IrExpr.Op.EQ
                && eq.left() instanceof IrExpr.SelfRef) {
            try {
                SymExpr expr = IrCompiler.compileSymExpr(eq.right());
                return intervalToIntSort(BoundAnalysis.bound(expr, hypothesesFromEnv(ctx)));
            } catch (CompileException | RuntimeException unused) {
                // Non-kernel term (e.g. an unresolved MethodCall at parse time) →
                // can't project; drop to the declared sort. Never throw.
                return null;
            }
        }
        // Anything else mentioning an escaping var can't be projected (a Bool pin
        // `@==(x>0)`, a non-pin predicate) → drop to the declared sort. Returning it
        // verbatim would leak a free variable into the consuming scope.
        return null;
    }

    /** Whether a refinement predicate references any of the given variable names. */
    private static boolean predicateMentionsAny(IrExpr predicate, Set<String> names) {
        return switch (predicate) {
            case IrExpr.Var v -> names.contains(v.name());
            case IrExpr.BinOp op ->
                    predicateMentionsAny(op.left(), names) || predicateMentionsAny(op.right(), names);
            case IrExpr.FieldAccess fa -> predicateMentionsAny(fa.base(), names);
            case IrExpr.Call c -> c.args().stream().anyMatch(a -> predicateMentionsAny(a, names));
            case IrExpr.Cast c -> predicateMentionsAny(c.value(), names);
            default -> false;
        };
    }

    /**
     * Every fact the env's bindings imply, as {@link BoundAnalysis} hypotheses. A refined binding
     * {@code x → [Int:@>=1]} implies {@code x >= 1}. A struct-typed binding implies each of its
     * refined fields' invariants: {@code this → Account} with {@code balance:[Int:@>=0]} implies
     * {@code this.balance >= 0}. A field invariant is nothing more than the field's own declared sort
     * surfaced as a fact about {@code name.field} — so an expression over {@code this.balance} bounds
     * through it, and {@link BoundAnalysis} still does all the arithmetic (this only sources the
     * operand bounds, never composes them). One field level; nested-struct fields are not unrolled.
     */
    private static List<SymExpr> hypothesesFromEnv(InferenceContext ctx) {
        List<SymExpr> hypotheses = new ArrayList<>();
        for (Map.Entry<String, IrSort> binding : ctx.typeEnv().entrySet()) {
            String name = binding.getKey();
            IrSort sort = binding.getValue();
            if (sort instanceof IrSort.Refined refined) {
                addFact(hypotheses, refined.predicate(), IrExpr.var(name));
            } else if (ctx.structDefs().get(baseName(sort)) instanceof IrSort.Structural struct) {
                for (String field : struct.members().keySet()) {
                    IrExpr access = new IrExpr.FieldAccess(IrExpr.var(name), field, Origin.NONE);
                    // The field's EFFECTIVE sort, via the field-access inference itself: for a
                    // narrowed receiver ([Account:@.balance>5]) this projects the tightened field
                    // sort [Int:@>5], not the declared [Int:@>=0]; for a bare receiver it falls back
                    // to the declared sort. Reuses inferFieldAccess rather than re-reading the struct.
                    if (infer(access, ctx) instanceof IrSort.Refined fieldEffective) {
                        addFact(hypotheses, fieldEffective.predicate(), access);
                    }
                }
            }
        }
        return hypotheses;
    }

    /**
     * Adds "{@code subject} satisfies {@code predicate}" (the predicate with {@code @} bound to
     * {@code subject}) to {@code out} — the one place a refinement becomes a {@link BoundAnalysis}
     * hypothesis, shared by variable and field-access subjects. Best-effort: a predicate or subject
     * outside the linear kernel is omitted, never thrown (a missing hypothesis only weakens a bound,
     * it can never fabricate one).
     */
    private static void addFact(List<SymExpr> out, IrExpr predicate, IrExpr subject) {
        try {
            out.add(Substitute.applySelf(
                    IrCompiler.compileSymExpr(predicate), IrCompiler.compileSymExpr(subject)));
        } catch (CompileException | RuntimeException outsideKernel) {
            // omit — best-effort
        }
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

        // An unconstrained branch (a bare base — predicate ≡ true) makes the whole
        // union unconstrained: `X | true ≡ true`, i.e. just the bare base. Claiming
        // `X | Y` instead would falsely exclude the unconstrained arm's values (a
        // lie). So if any arm carries no refinement, the union is the bare base.
        for (IrSort s : sorts) {
            if (!(s instanceof IrSort.Refined)) return IrSort.named(base);
        }

        IrExpr combined = null;
        for (IrSort s : sorts) {
            IrExpr pred = ((IrSort.Refined) s).predicate();
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
