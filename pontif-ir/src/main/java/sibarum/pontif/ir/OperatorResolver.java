package sibarum.pontif.ir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes binary operators to a concrete dispatch call, post-link.
 *
 * <p>Operators are mechanism-1 global multi-dispatch (docs/dispatch-unification.md):
 * a user type's {@code +} is a free overload, resolved by operand sorts over the
 * <em>linked</em> module regardless of which file declared it. The parser can't make
 * the BinOp-vs-Call decision — it sees only same-file declarations and no cross-module
 * types — so an operator over an imported struct used to stay a primitive {@code BinOp}
 * and fail at runtime. This pass makes that decision after linking, where every overload
 * and type is in hand (the same place {@link MethodResolver} resolves {@code recv.m()}).
 *
 * <p>For each {@link IrExpr.BinOp} (children first, so a chain like {@code n1*d2 + n2*d1}
 * types its dispatched inner {@code *} before the outer {@code +} is judged), the pass
 * resolves the operator against the declared overloads <em>by operand sort</em>: if a
 * homogeneous-or-matching overload {@code op(L, R)} exists for the operands' base sorts,
 * the BinOp is rewritten to {@code Call(<that overload's resolved name>, [l, r])} — the
 * exact (FQN-after-linking) key, so dispatch finds it. If no overload matches — primitive
 * arithmetic (built-ins aren't FunctionDecls), tuples, records, streams — the
 * {@code BinOp} stays untouched. So nothing without a real operator overload is ever
 * converted, and the emitted call name is always one the dispatch table holds.
 *
 * <p>Runs after {@link AliasResolver} (so refinement aliases resolve to their base, not a
 * spurious non-primitive name) and {@link MethodResolver} (so receiver-method results are
 * already Calls when operand types are inferred), and before {@link SortChecker}.
 */
public final class OperatorResolver {

    /** Overloadable operator symbols, by IR Op. Equality/logical/approx are not routed. */
    private static String dispatchSymbol(IrExpr.Op op) {
        return switch (op) {
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*"; case DIV -> "/";
            case MOD -> "%"; case POW -> "^";
            case LT -> "<"; case LE -> "<="; case GT -> ">"; case GE -> ">=";
            case EQ, NE, APPROX, AND, OR -> null;
        };
    }

    /** operator symbol → declared overloads of it (keyed by their full, post-link names). */
    private final Map<String, List<IrStmt.FunctionDecl>> operatorOverloads = new LinkedHashMap<>();

    private OperatorResolver(IrModule module) {
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd && fd.params().size() == 2) {
                String sym = simpleName(fd.name());
                if (isOperatorSymbol(sym)) {
                    operatorOverloads.computeIfAbsent(sym, k -> new ArrayList<>()).add(fd);
                }
            }
        }
    }

    public static IrModule resolve(IrModule module) {
        OperatorResolver r = new OperatorResolver(module);
        List<IrStmt> out = new ArrayList<>(module.statements().size());
        for (IrStmt stmt : module.statements()) out.add(r.rewriteStmt(stmt, InferenceContext.fromModule(module)));
        IrExpr main = module.main() == null ? null : r.rewriteExpr(module.main(), InferenceContext.fromModule(module));
        return new IrModule(module.name(), out, main);
    }

    private IrStmt rewriteStmt(IrStmt stmt, InferenceContext ctx) {
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

    private IrStmt.FunctionDecl rewriteFunction(IrStmt.FunctionDecl fd, InferenceContext ctx) {
        InferenceContext bodyCtx = ctx;
        for (IrParam p : fd.params()) bodyCtx = bodyCtx.withVar(p.name(), p.sort());
        return new IrStmt.FunctionDecl(fd.name(), fd.params(), fd.returnSort(),
                rewriteExpr(fd.body(), bodyCtx), fd.origin(), fd.topLevelLet(), fd.typeParams());
    }

    private IrExpr rewriteExpr(IrExpr e, InferenceContext ctx) {
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
                String sym = dispatchSymbol(op.op());
                String resolved = sym == null ? null
                        : resolveOverload(sym, NarrowingInference.infer(left, ctx),
                                NarrowingInference.infer(right, ctx));
                yield resolved != null
                        ? new IrExpr.Call(resolved, List.of(left, right), op.origin())
                        : new IrExpr.BinOp(op.op(), left, right, op.origin());
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
                // Correct a parse-time-routed operator call: it picks the LOCAL
                // overload by name, which is wrong across modules. Re-resolve by
                // operand sort; if a matching overload is found, retarget the call
                // to it. Abstract/unmatched operands (e.g. a type parameter) find
                // no match — leave the call as-is for runtime dispatch.
                String sym = simpleName(c.functionName());
                if (isOperatorSymbol(sym) && args.size() == 2) {
                    String resolved = resolveOverload(
                            sym, NarrowingInference.infer(args.get(0), ctx),
                            NarrowingInference.infer(args.get(1), ctx));
                    if (resolved != null) {
                        yield new IrExpr.Call(resolved, args, c.origin());
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
            case IrExpr.MethodCall mc -> throw MethodResolver.unresolved(mc, "OperatorResolver");
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

    /**
     * Resolves operator {@code sym} against its declared overloads by operand base
     * sort, returning the matching overload's full (post-link) name, or null when no
     * overload matches (so the BinOp stays — primitives, tuples, etc.). A match is a
     * 2-param overload whose param base names equal the operand base names. Most-specific
     * resolution is left to runtime dispatch when several declared overloads share a
     * symbol; here a base-name match is enough to (a) decide BinOp-vs-Call and (b) name
     * the dispatch key — the runtime still resolves among same-keyed overloads.
     */
    private String resolveOverload(String sym, IrSort leftSort, IrSort rightSort) {
        String lb = baseName(leftSort);
        String rb = baseName(rightSort);
        if (lb == null || rb == null) {
            return null;
        }
        for (IrStmt.FunctionDecl fd : operatorOverloads.getOrDefault(sym, List.of())) {
            String p0 = baseName(fd.params().get(0).sort());
            String p1 = baseName(fd.params().get(1).sort());
            if (lb.equals(p0) && rb.equals(p1)) {
                return fd.name();
            }
        }
        return null;
    }

    private static boolean isOperatorSymbol(String s) {
        return switch (s) {
            case "+", "-", "*", "/", "%", "^", "<", "<=", ">", ">=", "==", "!=" -> true;
            default -> false;
        };
    }

    /**
     * The local name after the module qualifier. The separator is the FIRST '/'
     * (a module FQN is dotted — `cott.traction.cd` — never contains '/'), so the
     * `/` operator (linked name `cott.traction.cd//`) correctly yields "/", not ""
     * (which a lastIndexOf would give, dropping the division overload).
     */
    private static String simpleName(String fqn) {
        // The module separator is the FIRST '/', but only when a non-empty module
        // prefix precedes it (`cott.traction.cd//` → "/"). A leading '/' (slash at
        // index 0) is the bare division operator itself, not a separator — return
        // the whole name ("/").
        int slash = fqn.indexOf('/');
        return slash > 0 ? fqn.substring(slash + 1) : fqn;
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
