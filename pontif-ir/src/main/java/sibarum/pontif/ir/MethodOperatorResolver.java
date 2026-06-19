package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;
import sibarum.pontif.core.QualifiedName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** operator member symbol → declared overloads of it (keyed by post-link name). */
    private final Map<String, List<IrStmt.FunctionDecl>> operatorOverloads = new LinkedHashMap<>();
    /** every name a {@code MethodCall} may resolve to (Type.method / Trait.method keys). */
    private final Set<String> methodKeys;
    private final Map<String, IrSort.Structural> structs;
    /** Cross-module ownership/import table for the visibility gate, or null for a
     *  bare single-file compile (nothing to gate — every name is local).
     *  WAR(link-provenance) Slice 1: now used only to build per-decl ModuleScopes;
     *  Slice 2 moves resolution per-module and Slice 3 drops this field. */
    private final ModuleSymbolTable table;
    /** Visibility view of the decl currently being rewritten — its own module's
     *  scope (own + imported-by-association). In the legacy whole-module path it is
     *  recomputed per declaration from the FQN; in the per-module link path
     *  ({@link #resolvePerModule}) the caller sets it per module and it is fixed. */
    private ModuleScope currentScope = ModuleScope.unrestricted();
    /** When true (the per-module link path, WAR(link-provenance) Slice 2),
     *  {@link #currentScope} is owned by the caller and NOT recomputed per
     *  declaration — resolution is gated in one fixed scope per module. */
    private boolean fixedScope = false;

    private MethodOperatorResolver(IrModule module, boolean resolveMethods, boolean routeOperators,
            ModuleSymbolTable table) {
        this.resolveMethods = resolveMethods;
        this.routeOperators = routeOperators;
        this.table = table;
        this.methodKeys = collectMethodKeys(module);
        this.structs = InferenceContext.fromModule(module).structDefs();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd && fd.params().size() == 2) {
                String sym = QualifiedName.memberOf(fd.name());
                if (isOperatorSymbol(sym)) {
                    operatorOverloads.computeIfAbsent(sym, k -> new ArrayList<>()).add(fd);
                }
            }
        }
    }

    /** Full resolution: methods AND operators (the run path), no visibility gate. */
    public static IrModule resolve(IrModule module) throws CompileException {
        return resolve(module, true, true, null);
    }

    /** Full resolution with the cross-module symbol table — enables the
     *  import-by-association visibility gate (Step B); pass the table the linker
     *  built for the combined module. A {@code null} table gates nothing. */
    public static IrModule resolve(IrModule module, ModuleSymbolTable table) throws CompileException {
        return resolve(module, true, true, table);
    }

    public static IrModule resolve(IrModule module, boolean resolveMethods, boolean routeOperators)
            throws CompileException {
        return resolve(module, resolveMethods, routeOperators, null);
    }

    public static IrModule resolve(IrModule module, boolean resolveMethods, boolean routeOperators,
            ModuleSymbolTable table) throws CompileException {
        MethodOperatorResolver r = new MethodOperatorResolver(module, resolveMethods, routeOperators, table);
        InferenceContext ctx = InferenceContext.fromModule(module);
        List<IrStmt> out = new ArrayList<>(module.statements().size());
        for (IrStmt stmt : module.statements()) out.add(r.rewriteStmt(stmt, ctx));
        r.currentScope = r.scopeFor(module.name());   // the entry module owns `main`
        IrExpr main = module.main() == null ? null : r.rewriteExpr(module.main(), ctx);
        return new IrModule(module.name(), out, main);
    }

    /**
     * The per-module link path (Option A; WAR(link-provenance) Slice 2): resolve an
     * already shape-resolved COMBINED module so that each declaration is gated in
     * ITS OWN module's scope. The typing / overload registry is the full combined
     * module — the migration error must be able to see an overload that <em>exists
     * but isn't imported</em> — while VISIBILITY is the per-module
     * {@link ModuleScope}. The link consumes {@code table} here, so nothing
     * downstream needs to re-thread it: this supersedes the old post-link
     * {@code resolve(module, table)} call as the sole visibility gate.
     *
     * <p>Result is identical to the legacy {@code resolve(combined, table)} (each
     * decl was already scoped by its FQN module) — the difference is structural:
     * the scope is assigned once per module here, not reconstructed per declaration
     * inside the walk, and it happens during linking rather than after.
     */
    public static IrModule resolvePerModule(IrModule combined, ModuleSymbolTable table)
            throws CompileException {
        MethodOperatorResolver r = new MethodOperatorResolver(combined, true, true, table);
        r.fixedScope = true;
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
            default -> stmt;
        };
    }

    /** The visibility scope for a decl owned by {@code module} ("" → unrestricted). */
    private ModuleScope scopeFor(String module) {
        return ModuleScope.forModule(module, table);
    }

    private IrStmt.FunctionDecl rewriteFunction(IrStmt.FunctionDecl fd, InferenceContext ctx)
            throws CompileException {
        // Legacy whole-module path recomputes the scope per decl from the FQN; the
        // per-module link path (fixedScope) keeps the caller's one scope per module.
        if (!fixedScope) this.currentScope = scopeFor(QualifiedName.parse(fd.name()).module());
        InferenceContext bodyCtx = ctx;
        for (IrParam p : fd.params()) bodyCtx = bodyCtx.withVar(p.name(), p.sort());
        IrExpr body = fd.body() == null ? null : rewriteExpr(fd.body(), bodyCtx);
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
                    IrSort leftSort = NarrowingInference.infer(left, ctx);
                    IrSort rightSort = NarrowingInference.infer(right, ctx);
                    String sym = dispatchSymbol(op.op());
                    String resolved = sym == null ? null
                            : resolveOverload(sym, leftSort, rightSort, op.origin());
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
                IrSort bound = NarrowingInference.infer(value, ctx);
                if (bound == null) bound = let.declaredSort();
                InferenceContext bodyCtx = bound != null ? ctx.withVar(let.name(), bound) : ctx;
                yield new IrExpr.LetIn(let.name(), let.declaredSort(), value,
                        rewriteExpr(let.body(), bodyCtx), let.origin(), let.claim());
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
                                NarrowingInference.infer(args.get(0), ctx),
                                NarrowingInference.infer(args.get(1), ctx), c.origin());
                        if (resolved != null) yield new IrExpr.Call(resolved, args, c.origin());
                    }
                }
                yield new IrExpr.Call(c.functionName(), args, c.origin());
            }
            case IrExpr.Lambda lam -> {
                InferenceContext bodyCtx = ctx;
                for (IrParam p : lam.params()) bodyCtx = bodyCtx.withVar(p.name(), p.sort());
                yield new IrExpr.Lambda(lam.params(), lam.returnSort(), rewriteExpr(lam.body(), bodyCtx), lam.origin());
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
                yield new IrExpr.Iterate(rewriteExpr(it.source(), ctx), it.element(), outs, arms, it.origin());
            }
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
        String typeName = baseName(NarrowingInference.infer(receiver, ctx));
        if (typeName != null) {
            String key = typeName + "." + mc.methodName();
            if (methodKeys.contains(key)) {
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
            throw new CompileException(
                    "No method '" + mc.methodName() + "' on type '" + typeName + "'", mc.origin());
        }
        throw new CompileException(
                "Cannot determine the type of the receiver of method '" + mc.methodName() + "'", mc.origin());
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
    private String resolveOverload(String sym, IrSort leftSort, IrSort rightSort, Origin origin)
            throws CompileException {
        String lb = baseName(leftSort);
        String rb = baseName(rightSort);
        if (lb == null || rb == null) return null;
        IrStmt.FunctionDecl invisible = null;
        for (IrStmt.FunctionDecl fd : operatorOverloads.getOrDefault(sym, List.of())) {
            String p0 = baseName(fd.params().get(0).sort());
            String p1 = baseName(fd.params().get(1).sort());
            if (lb.equals(p0) && rb.equals(p1)) {
                if (isVisibleHere(fd)) return fd.name();
                if (invisible == null) invisible = fd;   // matched by sort, but not imported
            }
        }
        if (invisible != null) throw notImportedError(sym, invisible, origin);
        return null;
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
     * Every name a {@code MethodCall} may legitimately resolve to: declared
     * function/method decls, trait-impl methods + attribute producers, and trait
     * contract methods (keyed {@code Trait.method}; dispatch's trait fallback
     * redirects those to the concrete type at the call).
     */
    private static Set<String> collectMethodKeys(IrModule module) {
        Set<String> keys = new LinkedHashSet<>();
        for (IrStmt stmt : module.statements()) {
            switch (stmt) {
                case IrStmt.FunctionDecl fd -> keys.add(fd.name());
                case IrStmt.TraitImpl ti -> {
                    for (IrStmt.FunctionDecl m : ti.methods()) keys.add(m.name());
                    for (IrStmt.FunctionDecl a : ti.attributeProducers()) keys.add(a.name());
                }
                case IrStmt.TypeAlias ta -> {
                    if (ta.sort() instanceof IrSort.Trait t) {
                        for (String methodName : t.methods().keySet()) keys.add(t.name() + "." + methodName);
                    }
                }
                default -> { }
            }
        }
        return keys;
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

    private static boolean isOperatorSymbol(String s) {
        return switch (s) {
            case "+", "-", "*", "/", "%", "^", "<", "<=", ">", ">=", "==", "!=" -> true;
            default -> false;
        };
    }

    /** The nominal base type name of a sort, or null if it has none. */
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
