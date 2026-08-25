package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;
import sibarum.pontif.core.QualifiedName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The unified post-link resolution pass: in ONE bottom-up tree walk it both
 * resolves instance-method calls ({@code recv.m(args)} →
 * {@code Call("Type.m", [recv, ...args])}) and routes binary operators to their
 * dispatch overload ({@code a + b} → {@code Call("module/+", [a, b])}).
 *
 * <p><b>Why one pass.</b> Method resolution and operator routing are mutually
 * dependent: typing the receiver of {@code (a + b).sum()} needs the {@code +}
 * already routed to a {@code Call} (so its result sort is known), while routing
 * the {@code +} in {@code m(a) + m(b)} needs the method calls already resolved
 * (so the operand sorts are known). A fixed order — the old
 * {@code MethodResolver}-then-{@code OperatorResolver} sequence — could satisfy
 * only one direction. Resolving each node from its <em>already-resolved
 * children</em> (true bottom-up) satisfies both, because neither kind of node
 * globally precedes the other: an inner operator becomes a {@code Call} before
 * its enclosing method receiver is typed, and an inner method becomes a
 * {@code Call} before its enclosing operator's operands are typed.
 *
 * <p><b>Two presets.</b> The pass is parameterized by {@code resolveMethods} and
 * {@code routeOperators}:
 * <ul>
 *   <li>The run path ({@link IrCompiler}) uses BOTH — full resolution.</li>
 *   <li>The conservation / receipt <em>report</em> paths use methods-only
 *       (operators left as parse-routed): their ledgers deliberately show the
 *       parse-time operator shape (see {@link MethodResolver}, the methods-only
 *       facade those paths call).</li>
 * </ul>
 *
 * <p>Runs after {@link AliasResolver} (so receiver/operand sorts are alias-free)
 * and before {@link SortChecker}.
 */
public final class MethodOperatorResolver {

    private final boolean resolveMethods;
    private final boolean routeOperators;
    private final Map<String, IrSort.Structural> structs;
    /** Top-level `let` name → its DECLARED sort (the nominal claim), the Declared record of the three
     *  (docs/type-records.md). A transparent alias binding records the structural sort (`_tuple`) as its
     *  value, keeping the declared name only in the `LetIn` claim; nominal (method) dispatch reads this
     *  when inference lost the name (an anonymous aggregate), so `let v:Vec3 = {…}; v.m()` dispatches on
     *  `Vec3`. Kept SEPARATE from the binding — reading it here never disturbs the transparent inferred
     *  sort the gates/refinement see. */
    private final Map<String, IrSort> declaredReturns = new LinkedHashMap<>();
    /** In-scope LOCAL binding name → its DECLARED claim (the annotation on a {@code let}), the Declared
     *  record for a param/local — the counterpart of {@link #declaredReturns} for non-top-level bindings.
     *  Maintained by the tree walk (pushed on a {@link IrExpr.LetIn}, cleared for a shadowing lambda
     *  param, isolated per function) so it always reflects the receiver binding's OWN annotation, never a
     *  name-collision. Nominal (method) dispatch reads it declared-first so a demoted {@code let b:Point =
     *  point3dValue} exposes only {@code Point}'s methods, even though the binding's Inferred sort (kept in
     *  the {@code InferenceContext} for transparency/refinement) is the concrete {@code Point3D}
     *  (docs/type-records.md; docs/type-system-roadmap.md §6.5/§6.6). */
    private final Map<String, IrSort> localClaims = new LinkedHashMap<>();
    /** Inference AND name-routing both go through the type-system facade (the single answerer): the
     *  operator/method routing tables now live on {@link InferenceContext} and are consulted via
     *  {@code dispatch()}, so this pass no longer keeps its own copies. */
    private final sibarum.pontif.types.TypeSystem types = sibarum.pontif.types.TypeSystem.standard();
    /** Visibility view used to gate operator/method routing. The whole-module
     *  {@code resolve(...)} entry points leave it {@linkplain ModuleScope#unrestricted()
     *  unrestricted} (single-file, or an already-gated no-op re-run); only the
     *  per-module link path ({@link #resolvePerModule}) sets it per module — the
     *  sole cross-module visibility gate (WAR(link-provenance)). */
    private ModuleScope currentScope = ModuleScope.unrestricted();

    private MethodOperatorResolver(IrModule module, boolean resolveMethods, boolean routeOperators) {
        this.resolveMethods = resolveMethods;
        this.routeOperators = routeOperators;
        this.structs = InferenceContext.fromModule(module).structDefs();
        // A top-level `let v:T = …` lowers to a 0-arg FunctionDecl whose body is a LetIn carrying the
        // declared sort in its claim (the binding/return sort is the transparent structural sort). Record
        // that claim as v's Declared sort for nominal dispatch.
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd && fd.topLevelLet()
                    && fd.body() instanceof IrExpr.LetIn letBody && letBody.claim() != null) {
                declaredReturns.put(fd.name(), letBody.claim());
            }
        }
    }

    /** Full resolution: methods AND operators (the run path). Unrestricted — for a
     *  bare single-file compile (nothing cross-module to gate) and for the no-op
     *  re-run over an already-linked module. Cross-module gating is the linker's
     *  job via {@link #resolvePerModule}. */
    public static IrModule resolve(IrModule module) throws CompileException {
        return resolve(module, true, true);
    }

    public static IrModule resolve(IrModule module, boolean resolveMethods, boolean routeOperators)
            throws CompileException {
        // Reject an unresolved free/static call before receiver-type inference runs — else a
        // missing import surfaces downstream as the misleading "cannot determine the receiver"
        // error. Idempotent: a no-op re-run on already-resolved IR (resolved operator/method
        // Calls are declared names or operator symbols). See CallNameCheck.
        CallNameCheck.check(module);
        MethodOperatorResolver r = new MethodOperatorResolver(module, resolveMethods, routeOperators);
        InferenceContext ctx = InferenceContext.fromModule(module);
        List<IrStmt> out = new ArrayList<>(module.statements().size());
        for (IrStmt stmt : module.statements()) out.add(r.rewriteStmt(stmt, ctx));
        IrExpr main = module.main() == null ? null : r.rewriteExpr(module.main(), ctx);
        return new IrModule(module.name(), out, main);
    }

    /**
     * The per-module link path (Option A; WAR(link-provenance)) and the <b>sole
     * cross-module visibility gate</b>: resolve an already shape-resolved COMBINED
     * module so that each declaration is gated in ITS OWN module's scope. The typing
     * / overload registry is the full combined module — the migration error must be
     * able to see an overload that <em>exists but isn't imported</em> — while
     * VISIBILITY is the per-module {@link ModuleScope}. The {@code table} is consumed
     * here, during linking; nothing downstream re-threads it (the whole-module
     * {@link #resolve(IrModule)} runs unrestricted, a no-op re-run on this output).
     *
     * <p>Scope is assigned once per module (grouped by the decls' FQN module), not
     * reconstructed per declaration inside the walk.
     */
    public static IrModule resolvePerModule(IrModule combined, ModuleSymbolTable table)
            throws CompileException {
        // The linker's confusing-error site: catch unresolved free/static calls (e.g. an
        // un-imported TractionCD.of) here, before receiver-type inference. See CallNameCheck.
        CallNameCheck.check(combined);
        MethodOperatorResolver r = new MethodOperatorResolver(combined, true, true);
        InferenceContext ctx = InferenceContext.fromModule(combined);
        Map<String, ModuleScope> scopes = new LinkedHashMap<>();
        String entry = combined.name();
        List<IrStmt> out = new ArrayList<>(combined.statements().size());
        for (IrStmt stmt : combined.statements()) {
            r.currentScope = scopes.computeIfAbsent(
                    moduleOf(stmt, entry), m -> ModuleScope.forModule(m, table));
            out.add(r.rewriteStmt(stmt, ctx));
        }
        r.currentScope = scopes.computeIfAbsent(entry, m -> ModuleScope.forModule(m, table));
        IrExpr main = combined.main() == null ? null : r.rewriteExpr(combined.main(), ctx);
        return new IrModule(entry, out, main);
    }

    /** The owning (FQN) module of a statement, read from its declared name; the
     *  {@code fallback} (the entry module) for statements with no resolvable body. */
    private static String moduleOf(IrStmt stmt, String fallback) {
        String fqn = switch (stmt) {
            case IrStmt.FunctionDecl fd -> fd.name();
            case IrStmt.TraitImpl ti -> traitImplAnchor(ti);
            default -> null;
        };
        return fqn == null ? fallback : QualifiedName.parse(fqn).module();
    }

    /** A trait impl's methods all live in the impl's module; anchor the scope on a
     *  method (or attribute producer) FQN, falling back to the type name. */
    private static String traitImplAnchor(IrStmt.TraitImpl ti) {
        if (!ti.methods().isEmpty()) return ti.methods().get(0).name();
        if (!ti.attributeProducers().isEmpty()) return ti.attributeProducers().get(0).name();
        return ti.typeName();
    }

    private IrStmt rewriteStmt(IrStmt stmt, InferenceContext ctx) throws CompileException {
        return switch (stmt) {
            case IrStmt.FunctionDecl fd -> rewriteFunction(fd, ctx);
            case IrStmt.TraitImpl ti -> {
                List<IrStmt.FunctionDecl> methods = new ArrayList<>(ti.methods().size());
                for (IrStmt.FunctionDecl m : ti.methods()) methods.add(rewriteFunction(m, ctx));
                List<IrStmt.FunctionDecl> producers = new ArrayList<>(ti.attributeProducers().size());
                for (IrStmt.FunctionDecl a : ti.attributeProducers()) producers.add(rewriteFunction(a, ctx));
                yield new IrStmt.TraitImpl(ti.typeName(), ti.traitName(), methods, producers,
                        ti.typeBindings(), ti.typeParams(), ti.traitTypeArgs(), ti.origin());
            }
            // A conductor's state initializers and reaction bodies contain method calls and
            // operators like any other code. Skipped by the old `default`, a state initializer
            // holding one failed with the internal-sounding "MethodResolver must eliminate
            // MethodCall before IrCompiler" — the pass that was supposed to eliminate it never
            // looked inside a conductor.
            case IrStmt.ConductorDecl cd -> cd.mapStateInits(e -> rewriteExpr(e, ctx));
            // Exhaustive from here: no method call or operator to route.
            case IrStmt.Proof p -> p;          // a proof tree is a marker call, routed by its own gate
            case IrStmt.ReturnProof rp -> rp;  // the case-function body's arms are inert guards
            case IrStmt.Coercion c -> c;       // lowered to a FunctionDecl before this pass runs
            case IrStmt.TypeAlias ta -> ta;
            case IrStmt.Spawn sp -> sp;
            case IrStmt.Requires rq -> rq;
            case IrStmt.Exports ex -> ex;
            case IrStmt.NoOp np -> np;
        };
    }

    private IrStmt.FunctionDecl rewriteFunction(IrStmt.FunctionDecl fd, InferenceContext ctx)
            throws CompileException {
        // currentScope is owned by the caller: unrestricted for the whole-module
        // resolve(...) paths, or the per-module scope set by resolvePerModule.
        InferenceContext bodyCtx = ctx;
        for (IrParam p : fd.params()) bodyCtx = bodyCtx.withVar(p.name(), p.sort());
        // A function body is its own lexical scope — it never sees another declaration's locals. Isolate
        // the local-claim scope so a `let` claim from one function/top-level-let can't leak into another.
        Map<String, IrSort> outerClaims = new LinkedHashMap<>(localClaims);
        localClaims.clear();
        IrExpr body;
        try {
            body = fd.body() == null ? null : rewriteExpr(fd.body(), bodyCtx);
        } finally {
            localClaims.clear();
            localClaims.putAll(outerClaims);
        }
        return new IrStmt.FunctionDecl(fd.name(), fd.params(), fd.returnSort(),
                body, fd.origin(), fd.topLevelLet(), fd.typeParams());
    }

    private IrExpr rewriteExpr(IrExpr e, InferenceContext ctx) throws CompileException {
        return switch (e) {
            case IrExpr.Lit l -> l;
            case IrExpr.Dec d -> d;
            case IrExpr.Chr c -> c;
            case IrExpr.Str s -> s;
            case IrExpr.Bool b -> b;
            case IrExpr.Var v -> v;
            case IrExpr.SelfRef s -> s;
            case IrExpr.DispatchRef d -> d;
            case IrExpr.BinOp op -> {
                IrExpr left = rewriteExpr(op.left(), ctx);
                IrExpr right = rewriteExpr(op.right(), ctx);
                if (routeOperators) {
                    IrSort leftSort = types.infer(left, ctx);
                    IrSort rightSort = types.infer(right, ctx);
                    String sym = dispatchSymbol(op.op());
                    String resolved = sym == null ? null
                            : resolveOverload(sym, leftSort, rightSort, op.origin(), ctx);
                    if (resolved != null) {
                        yield new IrExpr.Call(resolved, List.of(left, right), op.origin());
                    }
                    // No user overload matched: reject at compile time the
                    // applications the runtime would refuse, so no operator reaches
                    // runtime undefined (the mandate). Built-in primitives and
                    // concrete-struct pairs are decided here; abstract operands are
                    // deferred to the trait-bound check and the trait-operand rule.
                    checkOperatorComplete(op.op(), leftSort, rightSort, op.origin());
                }
                yield new IrExpr.BinOp(op.op(), left, right, op.origin());
            }
            case IrExpr.LetIn let -> {
                IrExpr value = rewriteExpr(let.value(), ctx);
                IrSort bound = types.infer(value, ctx);
                if (bound == null) bound = let.declaredSort();
                InferenceContext bodyCtx = bound != null ? ctx.withVar(let.name(), bound) : ctx;
                // The binding's own Declared claim governs nominal (method) dispatch through it in the
                // body (docs/type-records.md). A claimless local shadows any outer claim of the same name,
                // so record the (possibly absent) claim and restore on exit — the map tracks THIS
                // binding's annotation, never a name-collision with a top-level let or outer local.
                IrSort savedClaim = localClaims.get(let.name());
                boolean hadClaim = localClaims.containsKey(let.name());
                if (let.claim() != null) localClaims.put(let.name(), let.claim());
                else localClaims.remove(let.name());
                IrExpr newBody;
                try {
                    newBody = rewriteExpr(let.body(), bodyCtx);
                } finally {
                    if (hadClaim) localClaims.put(let.name(), savedClaim);
                    else localClaims.remove(let.name());
                }
                yield new IrExpr.LetIn(let.name(), let.declaredSort(), value,
                        newBody, let.origin(), let.claim());
            }
            case IrExpr.Call c -> {
                List<IrExpr> args = new ArrayList<>(c.args().size());
                for (IrExpr a : c.args()) args.add(rewriteExpr(a, ctx));
                // Correct a parse-time-routed operator call: parse routing picks
                // the LOCAL overload by name, which is wrong across modules.
                // Re-resolve by operand sort; unmatched operands (e.g. a type
                // parameter) find no overload and are left for runtime dispatch.
                if (routeOperators) {
                    String sym = QualifiedName.memberOf(c.functionName());
                    if (isOperatorSymbol(sym) && args.size() == 2) {
                        String resolved = resolveOverload(sym,
                                types.infer(args.get(0), ctx),
                                types.infer(args.get(1), ctx), c.origin(), ctx);
                        if (resolved != null) yield new IrExpr.Call(resolved, args, c.origin());
                    }
                }
                yield new IrExpr.Call(c.functionName(), args, c.origin());
            }
            case IrExpr.Lambda lam -> {
                InferenceContext bodyCtx = ctx;
                for (IrParam p : lam.params()) bodyCtx = bodyCtx.withVar(p.name(), p.sort());
                // A lambda closes over outer locals (keep their claims visible) but its OWN params must
                // not inherit an outer claim of the same name — a param routes on its declared sort (the
                // Inferred head), not a shadowed local's view. Drop param claims for the body, restore after.
                Map<String, IrSort> shadowed = new LinkedHashMap<>();
                for (IrParam p : lam.params()) {
                    if (localClaims.containsKey(p.name())) shadowed.put(p.name(), localClaims.get(p.name()));
                    localClaims.remove(p.name());
                }
                IrExpr lbody;
                try {
                    lbody = rewriteExpr(lam.body(), bodyCtx);
                } finally {
                    for (IrParam p : lam.params()) localClaims.remove(p.name());
                    localClaims.putAll(shadowed);
                }
                yield new IrExpr.Lambda(lam.params(), lam.returnSort(), lbody, lam.origin());
            }
            case IrExpr.Apply app -> {
                List<IrExpr> args = new ArrayList<>(app.args().size());
                for (IrExpr a : app.args()) args.add(rewriteExpr(a, ctx));
                yield new IrExpr.Apply(rewriteExpr(app.fn(), ctx), args, app.origin());
            }
            case IrExpr.Match m -> {
                List<IrExpr.MatchBranch> bs = new ArrayList<>(m.branches().size());
                for (IrExpr.MatchBranch b : m.branches()) {
                    InferenceContext armCtx = m.scrutinee() instanceof IrExpr.Var sv
                            ? ctx.withVar(sv.name(), b.pattern()) : ctx;
                    bs.add(new IrExpr.MatchBranch(b.pattern(), rewriteExpr(b.result(), armCtx)));
                }
                yield new IrExpr.Match(rewriteExpr(m.scrutinee(), ctx), bs, m.origin());
            }
            case IrExpr.Record r -> {
                Map<String, IrExpr> mem = new LinkedHashMap<>();
                for (Map.Entry<String, IrExpr> en : r.members().entrySet()) mem.put(en.getKey(), rewriteExpr(en.getValue(), ctx));
                yield new IrExpr.Record(r.typeName(), mem, r.runtimeChecks(), r.origin());
            }
            case IrExpr.FieldAccess fa -> new IrExpr.FieldAccess(rewriteExpr(fa.base(), ctx), fa.fieldName(), fa.origin());
            case IrExpr.MethodCall mc -> resolveMethodCall(mc, ctx);
            case IrExpr.Iterate it -> {
                List<IrExpr.OutputSpec> outs = new ArrayList<>(it.outputs().size());
                for (IrExpr.OutputSpec os : it.outputs())
                    outs.add(new IrExpr.OutputSpec(os.name(), os.kind(), os.init() == null ? null : rewriteExpr(os.init(), ctx)));
                List<IrExpr.Arm> arms = new ArrayList<>(it.arms().size());
                for (IrExpr.Arm arm : it.arms()) {
                    List<IrExpr.Write> ws = new ArrayList<>(arm.writes().size());
                    for (IrExpr.Write w : arm.writes())
                        ws.add(new IrExpr.Write(w.output(), w.key() == null ? null : rewriteExpr(w.key(), ctx), rewriteExpr(w.value(), ctx)));
                    arms.add(new IrExpr.Arm(arm.pattern(), ws));
                }
                List<IrExpr> coSources = new ArrayList<>(it.coSources().size());
                for (IrExpr cs : it.coSources()) coSources.add(rewriteExpr(cs, ctx));
                yield new IrExpr.Iterate(rewriteExpr(it.source(), ctx), coSources, it.element(), outs, arms, it.origin(), it.gpu());
            }
            case IrExpr.Emit em -> new IrExpr.Emit(
                    rewriteExpr(em.event(), ctx), rewriteExpr(em.body(), ctx), em.origin());
            case IrExpr.Cast cast -> new IrExpr.Cast(cast.targetSort(), rewriteExpr(cast.value(), ctx), cast.origin());
        };
    }

    private IrExpr resolveMethodCall(IrExpr.MethodCall mc, InferenceContext ctx) throws CompileException {
        // Receiver and args first — bottom-up, so an operator-result receiver
        // (a + b).m() has already become a Call (typed) by now.
        IrExpr receiver = rewriteExpr(mc.receiver(), ctx);
        List<IrExpr> args = new ArrayList<>(mc.args().size());
        for (IrExpr a : mc.args()) args.add(rewriteExpr(a, ctx));
        if (!resolveMethods) {
            // Methods-only is the only meaningful disable; this branch is for the
            // (unused) operators-only preset — keep the call symbolic.
            throw MethodResolver.unresolved(mc, "MethodOperatorResolver(routeOperators-only)");
        }
        // NOMINAL identity for method dispatch = the Declared record, falling back to the Inferred
        // sort's head (docs/type-records.md). Inference gives the transparent structural sort for a
        // declared alias binding (`_tuple`), which has no method; the declared claim (`Vec3`) is the
        // identity the method dispatches on.
        IrSort receiverSort = nominalReceiverSort(receiver, types.infer(receiver, ctx));
        // Cell[T] clocked-cell methods (docs/orchestration.md, §"State is a clocked cell") are a builtin
        // primitive: lower `this.f.apply(op)` / `.setNext(v)` / `.reset()` to a reserved `#cell-…#(Str(f),…)`
        // call the interpreter stages into the firing conductor's cell. Kept out of the general dispatch —
        // it's a primitive, like Int's operators, not a user-declared method.
        IrExpr cell = tryResolveCellMethod(receiverSort, mc, receiver, args);
        if (cell != null) return cell;
        // Intersection receiver: the some-branch member rule — resolve the method on
        // whichever branch declares it (`[A & B]` has A's methods and B's). A unique
        // branch answers; two branches routing it to different keys is ambiguous.
        List<IrSort> candidates = receiverSort instanceof IrSort.Intersection inter
                ? inter.branches() : java.util.Collections.singletonList(receiverSort);
        IrExpr resolved = null;
        String resolvedVia = null;
        for (IrSort cand : candidates) {
            String candName = baseName(cand);
            if (candName == null) continue;
            IrExpr r = tryResolveMethodOn(cand, candName, mc, receiver, args, ctx);
            if (r == null) continue;
            if (resolved != null && !candName.equals(resolvedVia)) {
                throw new CompileException(
                        "Method '" + mc.methodName() + "' is ambiguous across intersection branches '"
                                + resolvedVia + "' and '" + candName + "'", mc.origin());
            }
            resolved = r;
            resolvedVia = candName;
        }
        if (resolved != null) {
            return resolved;
        }
        // No branch resolved it — preserve the single-sort diagnostics.
        if (receiverSort instanceof IrSort.Intersection) {
            throw new CompileException(
                    "No method '" + mc.methodName() + "' on any branch of the receiver's sort",
                    mc.origin());
        }
        String typeName = baseName(receiverSort);
        if (typeName != null) {
            throw new CompileException(
                    "No method '" + mc.methodName() + "' on type '" + typeName + "'", mc.origin());
        }
        throw new CompileException(
                "Cannot determine the type of the receiver of method '" + mc.methodName() + "'", mc.origin());
    }

    /**
     * Resolves method {@code mc} against one candidate receiver sort {@code candSort}
     * (base name {@code typeName}) — a routable method key becomes the dispatch
     * {@code Call}, a callable field becomes an {@code Apply}, else null. Split out so
     * an intersection receiver can try each branch.
     */
    /** The Cell[T] methods that lower to reserved {@code #cell-…#} calls. */
    private static final java.util.Set<String> CELL_METHODS = java.util.Set.of("apply", "setNext", "reset");

    /**
     * Lowers a {@code Cell[T]} method call to its reserved {@code #cell-…#} form, or null if the receiver
     * isn't a Cell / the method isn't one of {@link #CELL_METHODS}. The receiver must be a direct conductor
     * field access ({@code this.field.apply(…)}) so the field name can name the cell to stage into — an
     * aliased receiver ({@code let c = this.f  c.apply(…)}) is rejected until cells are first-class values.
     */
    private IrExpr tryResolveCellMethod(IrSort receiverSort, IrExpr.MethodCall mc, IrExpr receiver,
            List<IrExpr> args) throws CompileException {
        if (!"Cell".equals(baseName(receiverSort)) || !CELL_METHODS.contains(mc.methodName())) return null;
        if (!(receiver instanceof IrExpr.FieldAccess fa)) {
            throw new CompileException(
                    "a Cell method (`" + mc.methodName() + "`) must be called directly on a conductor field "
                            + "— `this.field." + mc.methodName() + "(…)`", mc.origin());
        }
        List<IrExpr> cargs = new ArrayList<>(args.size() + 1);
        cargs.add(new IrExpr.Str(fa.fieldName(), mc.origin()));
        cargs.addAll(args);
        return new IrExpr.Call("#cell-" + mc.methodName().toLowerCase() + "#", cargs, mc.origin());
    }

    private IrExpr tryResolveMethodOn(
            IrSort candSort, String typeName, IrExpr.MethodCall mc,
            IrExpr receiver, List<IrExpr> args, InferenceContext ctx) {
        // Does base(receiver).method name a routable method key? The unified dispatch query answers
        // (it consults the routable-key set, trait-contract keys included); this pass forms the Call.
        sibarum.pontif.types.DispatchResult dr = types.dispatch(
                sibarum.pontif.types.DispatchQuery.forMethod(mc.methodName(), candSort), ctx);
        if (routes(dr)) {
            // Target the RESOLVED function's actual name — which the linker may have
            // module-qualified (a bare receiver nominal like a metareference's
            // `AlgebraicDispatch` routes to `pontif.algebra/AlgebraicDispatch.method`). Fall
            // back to `Type.method` for a trait-contract key with no concrete declaration.
            List<IrStmt.FunctionDecl> family = routingFamily(dr);
            String key = family.isEmpty()
                    ? typeName + "." + mc.methodName()
                    : family.get(0).name();
            List<IrExpr> withReceiver = new ArrayList<>(args.size() + 1);
            withReceiver.add(receiver);
            withReceiver.addAll(args);
            return new IrExpr.Call(key, withReceiver, mc.origin());
        }
        // Not a method — a field holding a callable, applied.
        IrSort.Structural def = structs.get(typeName);
        if (def != null && def.members().containsKey(mc.methodName())) {
            return new IrExpr.Apply(
                    new IrExpr.FieldAccess(receiver, mc.methodName(), mc.origin()), args, mc.origin());
        }
        return null;
    }

    /**
     * Resolves operator {@code sym} against its declared overloads by operand base
     * sort, returning the matching overload's full (post-link) name, or null when
     * no overload matches (so the BinOp stays — primitives, tuples, abstract
     * type-parameter operands). A base-name match is enough to (a) decide
     * BinOp-vs-Call and (b) name the dispatch key; most-specific selection among
     * same-keyed overloads is runtime dispatch's job.
     *
     * <p><b>Import-by-association visibility (Step B):</b> a matched overload is
     * only returned if it is visible to the calling module — i.e. that module owns
     * or imports ≥1 of the overload's signature types. A match that exists but
     * isn't imported is a compile error (the migration error), never a silent
     * miss. With no symbol table (single-file / unlinked) nothing is gated.
     */
    private String resolveOverload(String sym, IrSort leftSort, IrSort rightSort, Origin origin,
            InferenceContext ctx) throws CompileException {
        // The base-name routing family is the unified dispatch query at broad determinacy; visibility
        // gating (import-by-association) stays here because it needs this pass's per-module ModuleScope.
        List<IrStmt.FunctionDecl> family = routingFamily(types.dispatch(
                sibarum.pontif.types.DispatchQuery.forOperator(sym, leftSort, rightSort), ctx));
        IrStmt.FunctionDecl invisible = null;
        for (IrStmt.FunctionDecl fd : family) {
            if (isVisibleHere(fd)) return fd.name();
            if (invisible == null) invisible = fd;   // matched by sort, but not imported
        }
        if (invisible != null) throw notImportedError(sym, invisible, origin);
        return null;
    }

    /** The candidate family a coarse name-routing {@link sibarum.pontif.types.DispatchResult} names — the
     *  overloads the caller then gates / picks a name from. Residual/Unsatisfiable ⇒ no routable family. */
    private static List<IrStmt.FunctionDecl> routingFamily(sibarum.pontif.types.DispatchResult r) {
        return switch (r) {
            case sibarum.pontif.types.DispatchResult.Ambiguous a -> a.candidates();
            case sibarum.pontif.types.DispatchResult.Resolved res -> List.of(res.target());
            case sibarum.pontif.types.DispatchResult.Residual ignored -> List.of();
            case sibarum.pontif.types.DispatchResult.Unsatisfiable ignored -> List.of();
        };
    }

    /** Whether a name-routing query provably routes — a unique target or a matched family. */
    private static boolean routes(sibarum.pontif.types.DispatchResult r) {
        return r instanceof sibarum.pontif.types.DispatchResult.Resolved
                || r instanceof sibarum.pontif.types.DispatchResult.Ambiguous;
    }

    /**
     * Import-by-association visibility: an overload is visible to the current
     * scope iff that module owns or imports ≥1 of its <em>non-primitive</em>
     * signature types. Trivially true for the unrestricted scope (single-file/
     * unlinked, or an unknown calling module). The ownership/import test itself
     * lives on {@link ModuleScope} (WAR(link-provenance) Slice 1).
     */
    private boolean isVisibleHere(IrStmt.FunctionDecl fd) {
        if (!currentScope.restricts()) return true;
        for (IrParam p : fd.params()) {
            String t = baseName(p.sort());
            if (t != null && currentScope.ownsOrImports(t)) return true;
        }
        return false;
    }

    /** The migration error for a matched-but-unimported operator overload. */
    private CompileException notImportedError(String sym, IrStmt.FunctionDecl fd, Origin origin) {
        String declModule = QualifiedName.parse(fd.name()).module();
        String suggestType = null;
        for (IrParam p : fd.params()) {
            String t = baseName(p.sort());
            if (t == null) continue;
            QualifiedName qn = QualifiedName.parse(t);
            if (qn.module().equals(declModule)) { suggestType = qn.member(); break; }
        }
        String fix = suggestType != null
                ? "add `requires " + declModule + ".{" + suggestType + "}` to use it"
                : "import a type it is associated with";
        return new CompileException(
                "Operator '" + sym + "' resolves to an overload in module '" + declModule
                        + "', which '" + currentScope.module() + "' does not import — " + fix
                        + " (operators come with their operand types, by association).", origin);
    }

    /**
     * Rejects an operator application the runtime would refuse — the compile-time
     * half of "no operator reaches runtime undefined". Reached only for a
     * {@code BinOp} that resolved to no user overload.
     *
     * <ul>
     *   <li><b>Both concrete primitives</b> (Int/Bool/Decimal/Char/String): the
     *       built-in must define the op — delegated to {@link BuiltinOperators},
     *       the same predicate the interpreter consults, so gate and runtime
     *       cannot drift.</li>
     *   <li><b>At least one struct</b>, both operands concrete (a primitive or a
     *       declared struct): a user overload is required. Structural equality
     *       ({@code == != ~=}) is always defined; everything else with no
     *       declared overload is the runtime's {@code NoMatch}, rejected here.</li>
     *   <li><b>An abstract operand</b> (a type parameter, or a trait-typed value —
     *       a base that is neither a primitive nor a declared struct): deferred to
     *       the trait-bound check and the trait-operand rule (Step C).</li>
     * </ul>
     */
    private void checkOperatorComplete(IrExpr.Op op, IrSort leftSort, IrSort rightSort, Origin origin)
            throws CompileException {
        String lb = baseName(leftSort);
        String rb = baseName(rightSort);
        boolean lPrim = BuiltinOperators.isPrimitiveBase(lb);
        boolean rPrim = BuiltinOperators.isPrimitiveBase(rb);

        if (lPrim && rPrim) {
            if (!BuiltinOperators.acceptsPrimitive(op, lb, rb)) {
                throw new CompileException(
                        "Operator '" + BuiltinOperators.symbol(op) + "' is not defined for ("
                                + lb + ", " + rb + ") — " + BuiltinOperators.rejectionHint(op, lb, rb),
                        origin);
            }
            return;
        }

        // Step C — a bare trait-typed operand. Structural equality works on the
        // concrete runtime value, so it stays allowed; every other operator is
        // rejected. Operator contracts are homogeneous (`+(this.type, this.type)`),
        // which guarantees only same-type combination — but two values of a trait
        // type may be different implementers at runtime, so the pairing is not
        // provably total. The parametric bound `[type E:T]` ties both operands to
        // one concrete type and IS total (and is checked by checkOperatorBounds).
        IrSort.Trait leftTrait = leftSort instanceof IrSort.Trait t ? t : null;
        IrSort.Trait rightTrait = rightSort instanceof IrSort.Trait t ? t : null;
        if (leftTrait != null || rightTrait != null) {
            if (op == IrExpr.Op.EQ || op == IrExpr.Op.NE || op == IrExpr.Op.APPROX) {
                return;   // structural equality is always defined
            }
            // Stream concatenation: `+` is a BUILT-IN structural append on streams
            // (slice 2e — the same rule lifted to String +), NOT a trait contract
            // member, so it's defined on a Stream-typed operand the way structural
            // equality is. (Element-type compatibility of the result rides the §8.6
            // gap, as for any computed stream.)
            if (op == IrExpr.Op.ADD
                    && (isStreamTrait(leftTrait) || isStreamTrait(rightTrait))) {
                return;
            }
            String sym = BuiltinOperators.symbol(op);
            if (op == IrExpr.Op.AND || op == IrExpr.Op.OR) {
                throw new CompileException(
                        "Operator '" + sym + "' is not defined for trait-typed operands — "
                                + "logical operators need Bool operands", origin);
            }
            IrSort.Trait tr = leftTrait != null ? leftTrait : rightTrait;
            String guidance = tr.operators().containsKey(sym)
                    ? "trait '" + tr.name() + "' declares '" + sym + "' only for same-type operands "
                      + "(the homogeneous contract '" + sym + "(this.type, this.type)'), but two '"
                      + tr.name() + "' values may be different types at runtime — use a parametric "
                      + "bound `[type E:" + tr.name() + "]` so both operands are one concrete type"
                    : "trait '" + tr.name() + "' does not declare operator '" + sym + "' — declare it "
                      + "as a contract member and use a parametric bound `[type E:" + tr.name()
                      + "]`, or use a concrete type";
            throw new CompileException(
                    "Operator '" + sym + "' is not defined for the trait-typed operand '"
                            + tr.name() + "' — " + guidance, origin);
        }

        boolean lConcrete = lPrim || (lb != null && structs.containsKey(lb));
        boolean rConcrete = rPrim || (rb != null && structs.containsKey(rb));
        if (!(lConcrete && rConcrete)) {
            return;   // abstract operand — the trait-bound check / Step C governs it
        }
        if (op == IrExpr.Op.EQ || op == IrExpr.Op.NE || op == IrExpr.Op.APPROX) {
            return;   // structural equality is always defined
        }
        if (op == IrExpr.Op.AND || op == IrExpr.Op.OR) {
            throw new CompileException(
                    "Operator '" + BuiltinOperators.symbol(op) + "' is not defined for ("
                            + lb + ", " + rb + ") — logical operators need Bool operands", origin);
        }
        String owners = lb.equals(rb) ? lb : lb + " or " + rb;
        throw new CompileException(
                "Operator '" + BuiltinOperators.symbol(op) + "' is not defined for (" + lb + ", " + rb
                        + ") — no overload '" + BuiltinOperators.symbol(op) + "(" + lb + ", " + rb
                        + ")' is declared; define it in a module that owns " + owners, origin);
    }

    private static String dispatchSymbol(IrExpr.Op op) {
        return switch (op) {
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*"; case DIV -> "/";
            case MOD -> "%"; case POW -> "^";
            case LT -> "<"; case LE -> "<="; case GT -> ">"; case GE -> ">=";
            case EQ, NE, APPROX, AND, OR -> null;
        };
    }

    public static boolean isOperatorSymbol(String s) {  // reused by CallNameCheck + the dispatch resolver
        return switch (s) {
            case "+", "-", "*", "/", "%", "^", "<", "<=", ">", ">=", "==", "!=" -> true;
            default -> false;
        };
    }

    /** The nominal base type name of a sort, or null if it has none. */
    /** The Stream trait, bare or linker-qualified — the one trait with a built-in `+` (concat). */
    private static boolean isStreamTrait(IrSort.Trait t) {
        return t != null && (t.name().equals("Stream") || t.name().endsWith("/Stream"));
    }

    private static String baseName(IrSort sort) {
        return sort == null ? null : sort.baseName();
    }

    /**
     * The receiver's NOMINAL identity for method dispatch — <b>declared-first</b> (docs/type-records.md
     * "Which record each consumer reads"; docs/type-system-roadmap.md §6.5/§6.6). A binding's methods
     * follow the sort it was <em>declared</em> at, so a demoted {@code let b:Point = point3dValue} exposes
     * only {@code Point}'s methods even though the value is a {@code Point3D} — demotion is a view that
     * restricts static access. The Declared record is read from where it lives for that binding kind, and
     * only from a source that is unambiguously THIS binding's (never a name-collision):
     * <ul>
     *   <li>A param/local reference lowers to a {@link IrExpr.Var}; its Declared claim (if any) is in
     *       {@link #localClaims}, tracked with lexical scope. Read it first — this closes the demoted-local
     *       view leak (roadmap §6.6). A param, or a {@code let} with no annotation, has no claim and falls
     *       through to the Inferred head (which for a param already IS its declared sort).</li>
     *   <li>A top-level {@code let} / 0-arg function reference lowers to a 0-arg {@link IrExpr.Call}; its
     *       binding sort is already the declared narrowing, so the Inferred head suffices, EXCEPT when
     *       inference lost the name (a transparent-alias binding whose Inferred sort is {@code _tuple}) —
     *       then recover the Declared name from {@link #declaredReturns}. That map is name-keyed and holds
     *       only top-level lets, so it is safe here: only a {@code Call} names one; a shadowing local is a
     *       {@code Var}, handled above and never routed through it.</li>
     * </ul>
     */
    private IrSort nominalReceiverSort(IrExpr receiver, IrSort inferred) {
        if (receiver instanceof IrExpr.Var v) {
            IrSort claim = localClaims.get(v.name());
            return claim != null ? claim : inferred;   // the local binding's OWN declared view, else Inferred head
        }
        String inferredBase = baseName(inferred);
        if (inferredBase != null && !"_tuple".equals(inferredBase)) {
            return inferred;  // top-level/computed receiver with a nominal head — use it
        }
        String name = receiver instanceof IrExpr.Call c ? c.functionName() : null;
        IrSort declared = name == null ? null : declaredReturns.get(name);
        return declared != null ? declared : inferred;
    }
}
