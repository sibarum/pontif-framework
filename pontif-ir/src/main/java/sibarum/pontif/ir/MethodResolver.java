package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves {@link IrExpr.MethodCall} nodes to ordinary
 * {@code Call("Type.method", [receiver, ...args])} dispatch calls, using the
 * receiver's <em>inferred type</em> — after every declaration in the module is
 * registered.
 *
 * <p>This is the home of instance-method resolution, moved out of the parser
 * (where it was a single forward pass gated on the method already having been
 * seen, so a method could not be called above its declaration, recurse via
 * {@code this.m()}, or mutually recurse). Resolution here is order-independent
 * for the same reason free-function dispatch is: the whole module is in hand.
 *
 * <p>Runs after {@link AliasResolver} (so receiver sorts are alias-free) and
 * before {@link SortChecker} (which validates field accesses and would reject a
 * {@code FieldAccess(recv, "m")} placeholder). Receivers are resolved
 * bottom-up, so by the time an outer receiver is typed its inner method calls
 * are already plain {@code Call}s.
 */
public final class MethodResolver {

    private MethodResolver() {}

    /** Internal-invariant breach: a {@link IrExpr.MethodCall} survived to a later phase. */
    public static IllegalStateException unresolved(IrExpr.MethodCall mc, String phase) {
        return new IllegalStateException(
                "MethodResolver must eliminate MethodCall before " + phase
                        + " — saw an unresolved '" + mc.methodName() + "' call");
    }

    public static IrModule resolve(IrModule module) throws CompileException {
        InferenceContext base = InferenceContext.fromModule(module);
        Set<String> methodKeys = collectMethodKeys(module);
        Map<String, IrSort.Structural> structs = base.structDefs();

        List<IrStmt> out = new ArrayList<>(module.statements().size());
        for (IrStmt stmt : module.statements()) {
            out.add(rewriteStmt(stmt, base, methodKeys, structs));
        }
        IrExpr main = module.main() == null
                ? null
                : rewriteExpr(module.main(), base, methodKeys, structs);
        return new IrModule(module.name(), out, main);
    }

    /**
     * Every name a {@code MethodCall} may legitimately resolve to: declared
     * function/method decls (methods are {@code FunctionDecl}s keyed
     * {@code Type.method}), trait-impl methods and attribute producers, and
     * trait <em>contract</em> methods (keyed {@code Trait.method} — dispatch's
     * trait fallback redirects those to the concrete type at the call).
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
                        for (String methodName : t.methods().keySet()) {
                            keys.add(t.name() + "." + methodName);
                        }
                    }
                }
                default -> { /* nothing else declares a callable */ }
            }
        }
        return keys;
    }

    private static IrStmt rewriteStmt(
            IrStmt stmt, InferenceContext ctx,
            Set<String> methodKeys, Map<String, IrSort.Structural> structs)
            throws CompileException {
        return switch (stmt) {
            case IrStmt.FunctionDecl fd -> rewriteFunction(fd, ctx, methodKeys, structs);
            case IrStmt.TraitImpl ti -> {
                List<IrStmt.FunctionDecl> methods = new ArrayList<>(ti.methods().size());
                for (IrStmt.FunctionDecl m : ti.methods()) {
                    methods.add(rewriteFunction(m, ctx, methodKeys, structs));
                }
                List<IrStmt.FunctionDecl> producers = new ArrayList<>(ti.attributeProducers().size());
                for (IrStmt.FunctionDecl a : ti.attributeProducers()) {
                    producers.add(rewriteFunction(a, ctx, methodKeys, structs));
                }
                yield new IrStmt.TraitImpl(ti.typeName(), ti.traitName(), methods, producers, ti.typeBindings(), ti.origin());
            }
            // TypeAlias carries no expression; Proof/ReturnProof trees are
            // symbolic (never method calls) and stay verbatim.
            default -> stmt;
        };
    }

    private static IrStmt.FunctionDecl rewriteFunction(
            IrStmt.FunctionDecl fd, InferenceContext ctx,
            Set<String> methodKeys, Map<String, IrSort.Structural> structs)
            throws CompileException {
        InferenceContext bodyCtx = ctx;
        for (IrParam p : fd.params()) {
            bodyCtx = bodyCtx.withVar(p.name(), p.sort());
        }
        IrExpr body = fd.body() == null
                ? null
                : rewriteExpr(fd.body(), bodyCtx, methodKeys, structs);
        return new IrStmt.FunctionDecl(
                fd.name(), fd.params(), fd.returnSort(), body, fd.origin(), fd.topLevelLet(),
                fd.typeParams());
    }

    private static IrExpr rewriteExpr(
            IrExpr e, InferenceContext ctx,
            Set<String> methodKeys, Map<String, IrSort.Structural> structs)
            throws CompileException {
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
                    rewriteExpr(op.left(), ctx, methodKeys, structs),
                    rewriteExpr(op.right(), ctx, methodKeys, structs),
                    op.origin());
            case IrExpr.LetIn l -> {
                IrExpr value = rewriteExpr(l.value(), ctx, methodKeys, structs);
                IrSort bound = NarrowingInference.infer(value, ctx);
                if (bound == null) bound = l.declaredSort();
                InferenceContext bodyCtx = ctx.withVar(l.name(), bound);
                yield new IrExpr.LetIn(
                        l.name(), l.declaredSort(), value,
                        rewriteExpr(l.body(), bodyCtx, methodKeys, structs),
                        l.origin(), l.claim());
            }
            case IrExpr.Call c -> new IrExpr.Call(
                    c.functionName(), rewriteArgs(c.args(), ctx, methodKeys, structs), c.origin());
            case IrExpr.Lambda lam -> {
                InferenceContext bodyCtx = ctx;
                for (IrParam p : lam.params()) bodyCtx = bodyCtx.withVar(p.name(), p.sort());
                yield new IrExpr.Lambda(
                        lam.params(), lam.returnSort(),
                        rewriteExpr(lam.body(), bodyCtx, methodKeys, structs), lam.origin());
            }
            case IrExpr.Apply app -> new IrExpr.Apply(
                    rewriteExpr(app.fn(), ctx, methodKeys, structs),
                    rewriteArgs(app.args(), ctx, methodKeys, structs), app.origin());
            case IrExpr.Match m -> {
                List<IrExpr.MatchBranch> bs = new ArrayList<>(m.branches().size());
                for (IrExpr.MatchBranch b : m.branches()) {
                    // A match arm narrows the scrutinee var to the pattern; bind
                    // it so a method call on the scrutinee inside the arm types.
                    InferenceContext armCtx = ctx;
                    if (m.scrutinee() instanceof IrExpr.Var sv) {
                        armCtx = armCtx.withVar(sv.name(), b.pattern());
                    }
                    bs.add(new IrExpr.MatchBranch(
                            b.pattern(), rewriteExpr(b.result(), armCtx, methodKeys, structs)));
                }
                yield new IrExpr.Match(
                        rewriteExpr(m.scrutinee(), ctx, methodKeys, structs), bs, m.origin());
            }
            case IrExpr.Record r -> {
                Map<String, IrExpr> mem = new LinkedHashMap<>();
                for (Map.Entry<String, IrExpr> en : r.members().entrySet()) {
                    mem.put(en.getKey(), rewriteExpr(en.getValue(), ctx, methodKeys, structs));
                }
                yield new IrExpr.Record(r.typeName(), mem, r.runtimeChecks(), r.origin());
            }
            case IrExpr.FieldAccess fa -> new IrExpr.FieldAccess(
                    rewriteExpr(fa.base(), ctx, methodKeys, structs), fa.fieldName(), fa.origin());
            case IrExpr.MethodCall mc -> resolveMethodCall(mc, ctx, methodKeys, structs);
        };
    }

    private static List<IrExpr> rewriteArgs(
            List<IrExpr> args, InferenceContext ctx,
            Set<String> methodKeys, Map<String, IrSort.Structural> structs)
            throws CompileException {
        List<IrExpr> out = new ArrayList<>(args.size());
        for (IrExpr a : args) out.add(rewriteExpr(a, ctx, methodKeys, structs));
        return out;
    }

    private static IrExpr resolveMethodCall(
            IrExpr.MethodCall mc, InferenceContext ctx,
            Set<String> methodKeys, Map<String, IrSort.Structural> structs)
            throws CompileException {
        // Receiver and args first (bottom-up: inner method calls become Calls
        // before this receiver is typed).
        IrExpr receiver = rewriteExpr(mc.receiver(), ctx, methodKeys, structs);
        List<IrExpr> args = rewriteArgs(mc.args(), ctx, methodKeys, structs);

        String typeName = baseName(NarrowingInference.infer(receiver, ctx));

        if (typeName != null) {
            String key = typeName + "." + mc.methodName();
            if (methodKeys.contains(key)) {
                List<IrExpr> withReceiver = new ArrayList<>(args.size() + 1);
                withReceiver.add(receiver);
                withReceiver.addAll(args);
                return new IrExpr.Call(key, withReceiver, mc.origin());
            }
            // Not a method — a field holding a callable, applied: leave a genuine
            // Apply(FieldAccess(...)) for the normal apply path to handle.
            IrSort.Structural def = structs.get(typeName);
            if (def != null && def.members().containsKey(mc.methodName())) {
                return new IrExpr.Apply(
                        new IrExpr.FieldAccess(receiver, mc.methodName(), mc.origin()),
                        args, mc.origin());
            }
            throw new CompileException(
                    "No method '" + mc.methodName() + "' on type '" + typeName + "'",
                    mc.origin());
        }
        throw new CompileException(
                "Cannot determine the type of the receiver of method '"
                        + mc.methodName() + "'",
                mc.origin());
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
