package sibarum.pontif.ir;

import sibarum.pontif.types.TypeSystem;
import sibarum.pontif.core.Origin;

import java.util.ArrayList;
import java.util.List;

/**
 * The call gate's classifier walk (WAR(dependent-sorts), slice 2). For every
 * call site in a module, asks {@link StaticDispatch#classify} the routing
 * question — does this call provably route to an overload (PASSED), is it a
 * provable failure (FAILED), or undecided (RESIDUAL)?
 *
 * <p><b>Slice 2 step (d) — MEASURE FIRST.</b> Today this is consumed
 * <em>report-only</em> (see {@code PontifCompiler.reportCallGate}): it logs the
 * FAILED/RESIDUAL counts over the whole suite so the war's migration cost is a
 * measured number, not an assumption (docs/dependent-sorts.md §5). The gate that
 * turns FAILED into a compile error (step (c)) is the same walk with a policy
 * flip — it is deliberately <em>not</em> wired yet.
 *
 * <p>The walk threads in-scope narrowings exactly as {@link NarrowingInference}
 * does — param seedings, {@code let} bindings, {@code match}-arm hypotheses,
 * iteration element patterns — so a call inside a narrowed branch is classified
 * against the hypotheses that branch grants. Argument narrowings come from
 * {@link NarrowingInference#infer}; the per-call discharge is reused wholesale.
 *
 * <p>Scope: only {@link IrExpr.Call} sites whose name has registered overloads
 * are classified — the gate's jurisdiction. A bare name with no overloads
 * (builtins, unknowns) is out of scope and skipped (not reported as RESIDUAL).
 * {@link IrExpr.MethodCall} is resolved by a separate mechanism and is not
 * classified here.
 */
public final class CallGate {

    private CallGate() {}

    /**
     * One classified call site. {@code detail} is a human-readable dump of the
     * argument narrowings and candidate param sorts — populated for FAILED sites
     * (slice 2 step (d) triage), empty otherwise.
     */
    public record CallSite(String functionName, StaticDispatch.Verdict verdict,
                           Origin origin, String detail) {
        public CallSite(String functionName, StaticDispatch.Verdict verdict, Origin origin) {
            this(functionName, verdict, origin, "");
        }
    }

    /** The classified call sites of a module, in encounter order. */
    public record Report(List<CallSite> calls) {
        public Report {
            calls = List.copyOf(calls);
        }

        public long count(StaticDispatch.Verdict v) {
            return calls.stream().filter(c -> c.verdict() == v).count();
        }

        public List<CallSite> of(StaticDispatch.Verdict v) {
            return calls.stream().filter(c -> c.verdict() == v).toList();
        }
    }

    /**
     * Classifies every in-jurisdiction call in {@code module}. Pure — no errors,
     * no side effects; the caller decides policy (report vs. reject).
     */
    public static Report walk(IrModule module) {
        InferenceContext ctx = InferenceContext.fromModule(module);
        List<CallSite> sink = new ArrayList<>();
        for (IrStmt s : module.statements()) {
            collectFromStatement(s, ctx, sink);
        }
        // The top-level expression is `module.main()`, not a statement — walk it too,
        // so a bare top-level call (`h(-3)`) is gated like any other call site.
        if (module.main() != null) {
            walkExpr(module.main(), ctx, sink);
        }
        return new Report(sink);
    }

    // --- Statement entry points -------------------------------------------

    private static void collectFromStatement(IrStmt s, InferenceContext ctx, List<CallSite> sink) {
        switch (s) {
            case IrStmt.FunctionDecl fd -> walkExpr(fd.body(), seedParams(ctx, fd.params()), sink);
            case IrStmt.TraitImpl ti -> {
                for (IrStmt.FunctionDecl m : ti.methods()) {
                    walkExpr(m.body(), seedParams(ctx, m.params()), sink);
                }
                for (IrStmt.FunctionDecl a : ti.attributeProducers()) {
                    walkExpr(a.body(), seedParams(ctx, a.params()), sink);
                }
            }
            case IrStmt.ReturnProof rp -> {
                if (rp.body() != null) {
                    walkExpr(rp.body(), seedParams(ctx, rp.params()), sink);
                }
            }
            case IrStmt.Coercion c -> walkExpr(c.body(), bind(ctx, c.paramName(), c.sourceSort()), sink);
            // Proof trees are unevaluated symbolic literals; TypeAlias/Requires/
            // Exports/NoOp carry no call sites.
            default -> { }
        }
    }

    /** Triage dump for a FAILED site: the arg narrowings and each overload's param sorts. */
    private static String failedDetail(List<IrStmt.FunctionDecl> overloads, List<IrSort> argNarrowings) {
        StringBuilder sb = new StringBuilder("args=").append(argNarrowings);
        sb.append(" vs overloads=[");
        for (int i = 0; i < overloads.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(overloads.get(i).params().stream().map(IrParam::sort).toList());
        }
        return sb.append("]").toString();
    }

    /**
     * Tags a RESIDUAL call for the no-lie-sweep measurement (does promoting
     * RESIDUAL→error have legitimate targets, or would it reject valid code?):
     * <ul>
     *   <li>{@code residual:type-only} — no arity-matching overload param carries a
     *       refinement predicate; the no-lie rule (obligation = {@code arg ⊨ pred})
     *       doesn't apply. Erroring here would reject ordinary type/union narrowing.</li>
     *   <li>{@code residual:multi-overload} — &gt;1 arity-matching overload, at least
     *       one refined: almost always exhaustively-covered recursion (`sum(n-1)` under
     *       {[Int:0],[Int:@>0]}). The overload SET covers the arg; erroring would
     *       reject valid recursion. Needs overload-union exhaustiveness, not a flip.</li>
     *   <li>{@code residual:obligation} — a single refined overload whose predicate the
     *       arg can't prove: the genuine no-lie target (the §0 holes' shape).</li>
     * </ul>
     */
    private static String residualCategory(List<IrStmt.FunctionDecl> overloads, int arity) {
        List<IrStmt.FunctionDecl> arityMatch = overloads.stream()
                .filter(o -> o.params().size() == arity).toList();
        boolean anyRefined = arityMatch.stream()
                .anyMatch(o -> o.params().stream().anyMatch(p -> mentionsRefinement(p.sort())));
        if (!anyRefined) return "residual:type-only";
        if (arityMatch.size() > 1) return "residual:multi-overload";
        return "residual:obligation";  // detail appended by caller for triage
    }

    /** As {@link #residualCategory} but with the arg/param dump for obligation-residuals. */
    private static String residualDetail(List<IrStmt.FunctionDecl> overloads, List<IrSort> argNarrowings) {
        String cat = residualCategory(overloads, argNarrowings.size());
        return cat.equals("residual:obligation")
                ? cat + "  " + failedDetail(overloads, argNarrowings) : cat;
    }

    /** Whether an IrSort carries a refinement predicate anywhere (recursively). */
    private static boolean mentionsRefinement(IrSort sort) {
        return switch (sort) {
            case IrSort.Refined ignored -> true;
            case IrSort.Structural s -> s.members().values().stream().anyMatch(CallGate::mentionsRefinement);
            case IrSort.Union u -> u.branches().stream().anyMatch(CallGate::mentionsRefinement);
            case IrSort.Intersection i -> i.branches().stream().anyMatch(CallGate::mentionsRefinement);
            default -> false;
        };
    }

    private static InferenceContext seedParams(InferenceContext ctx, List<IrParam> params) {
        InferenceContext seeded = ctx;
        for (IrParam p : params) {
            seeded = bind(seeded, p.name(), p.sort());
        }
        return seeded;
    }

    /** {@code withVar} that tolerates a null sort (no narrowing → leave unbound). */
    private static InferenceContext bind(InferenceContext ctx, String name, IrSort sort) {
        return sort == null ? ctx : ctx.withVar(name, sort);
    }

    // --- Expression walk (scope-threaded, mirrors NarrowingInference) ------

    private static void walkExpr(IrExpr expr, InferenceContext ctx, List<CallSite> sink) {
        switch (expr) {
            case IrExpr.Call c -> {
                List<IrStmt.FunctionDecl> overloads = ctx.overloads().get(c.functionName());
                if (overloads != null && !overloads.isEmpty()) {
                    List<IrSort> argNarrowings = new ArrayList<>(c.args().size());
                    for (IrExpr arg : c.args()) {
                        // inferArg (not infer): bound a value-pin over the in-scope
                        // hypotheses so a decremented/recursive arg discharges against a
                        // weaker param refinement (n-1 under n>0 → [Int:@>=0]). Safe
                        // because the gate's FAILED is disjoint-based (gateFit).
                        argNarrowings.add(TypeSystem.standard().inferArg(arg, ctx));
                    }
                    StaticDispatch.Verdict v =
                            StaticDispatch.classify(overloads, argNarrowings, ctx.sortRegistry());
                    String detail = switch (v) {
                        case FAILED -> failedDetail(overloads, argNarrowings);
                        // Categorize RESIDUAL for the no-lie-sweep measurement: is the
                        // undecided verdict a genuine unproven refinement PREDICATE
                        // obligation, or merely type/union narrowing the no-lie rule
                        // doesn't govern? And is the function multi-overload (likely
                        // exhaustively-covered recursion — valid, not a target)?
                        case RESIDUAL -> residualDetail(overloads, argNarrowings);
                        case PASSED -> "";
                    };
                    sink.add(new CallSite(c.functionName(), v, c.origin(), detail));
                }
                for (IrExpr arg : c.args()) {
                    walkExpr(arg, ctx, sink);
                }
            }
            case IrExpr.LetIn let -> {
                walkExpr(let.value(), ctx, sink);
                IrSort narrowing = TypeSystem.standard().infer(let.value(), ctx);
                IrSort bound = narrowing != null ? narrowing : let.declaredSort();
                walkExpr(let.body(), bind(ctx, let.name(), bound), sink);
            }
            case IrExpr.Match m -> {
                walkExpr(m.scrutinee(), ctx, sink);
                for (IrExpr.MatchBranch branch : m.branches()) {
                    InferenceContext armCtx = ctx;
                    if (m.scrutinee() instanceof IrExpr.Var v
                            && (branch.pattern() instanceof IrSort.Refined
                                || branch.pattern() instanceof IrSort.Structural)) {
                        armCtx = bind(ctx, v.name(), branch.pattern());
                    }
                    walkExpr(branch.result(), armCtx, sink);
                }
            }
            case IrExpr.Iterate it -> {
                walkExpr(it.source(), ctx, sink);
                for (IrExpr.OutputSpec os : it.outputs()) {
                    if (os.init() != null) {
                        walkExpr(os.init(), ctx, sink);
                    }
                }
                for (IrExpr.Arm arm : it.arms()) {
                    InferenceContext armCtx = bind(ctx, it.element(), arm.pattern());
                    for (IrExpr.Write w : arm.writes()) {
                        if (w.key() != null) {
                            walkExpr(w.key(), armCtx, sink);
                        }
                        walkExpr(w.value(), armCtx, sink);
                    }
                }
            }
            case IrExpr.Lambda lam -> walkExpr(lam.body(), seedParams(ctx, lam.params()), sink);
            case IrExpr.Apply ap -> {
                walkExpr(ap.fn(), ctx, sink);
                for (IrExpr arg : ap.args()) {
                    walkExpr(arg, ctx, sink);
                }
            }
            case IrExpr.BinOp op -> {
                walkExpr(op.left(), ctx, sink);
                walkExpr(op.right(), ctx, sink);
            }
            case IrExpr.Record r -> {
                for (IrExpr member : r.members().values()) {
                    walkExpr(member, ctx, sink);
                }
            }
            case IrExpr.FieldAccess fa -> walkExpr(fa.base(), ctx, sink);
            case IrExpr.MethodCall mc -> {
                walkExpr(mc.receiver(), ctx, sink);
                for (IrExpr arg : mc.args()) {
                    walkExpr(arg, ctx, sink);
                }
            }
            case IrExpr.Cast cast -> walkExpr(cast.value(), ctx, sink);
            case IrExpr.Emit em -> {
                walkExpr(em.event(), ctx, sink);
                walkExpr(em.body(), ctx, sink);
            }
            // Leaves: nothing to walk.
            case IrExpr.Lit ignored -> { }
            case IrExpr.Dec ignored -> { }
            case IrExpr.Chr ignored -> { }
            case IrExpr.Str ignored -> { }
            case IrExpr.Bool ignored -> { }
            case IrExpr.Var ignored -> { }
            case IrExpr.SelfRef ignored -> { }
            case IrExpr.DispatchRef ignored -> { }
        }
    }
}
