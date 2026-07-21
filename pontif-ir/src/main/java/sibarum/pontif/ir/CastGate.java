package sibarum.pontif.ir;

import sibarum.pontif.types.Assignability;
import sibarum.pontif.types.AssignabilityContext;
import sibarum.pontif.types.TypeSystem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The cast gate (roadmap §4.5 item 3): a {@code (Target:value)} cast must have a legal path at
 * <em>compile</em> time, so the runtime never throws on a cast (§1d — no unproven runtime failure).
 * {@link IrInterpreter#evalCast} executes a cast exactly two ways: render to {@code String} (from a
 * renderable primitive — {@code Int}/{@code Decimal}/{@code Char}/{@code Bool}/{@code String}), or
 * dispatch a user-defined {@code cast Target:(x:Source)} coercion whose source the value satisfies. A
 * cast that is neither — and whose value's sort is statically known — is rejected here instead of
 * throwing "No coercion" / "cannot render" at run time.
 *
 * <p>Sound and conservative: when the value's sort is unknown ({@code _}) the gate abstains (never a
 * false reject), and a structural/refined target (no nominal head) likewise abstains. It threads scope
 * exactly as {@link NarrowingInference} does (param seedings, {@code let}/{@code match}/lambda), so a
 * cast inside a narrowed branch sees the right value sort.
 *
 * <p><b>NB</b> the language's aspirational "cast law" structural retag (sibling re-tag, narrow, which
 * {@code Assignability.cast} would license) is <em>not</em> runtime-implemented — so this gate matches
 * the runtime's real criteria (render + user coercion), not {@code Assignability.cast}. If/when the
 * runtime grows structural retag, this gate gains that path.
 */
public final class CastGate {

    private CastGate() {}

    /** The primitive sorts {@link IrInterpreter#evalCast} can render to {@code String}. */
    private static final Set<String> RENDERABLE = Set.of("Int", "Decimal", "Char", "Bool", "String");

    /** The first cast with no legal path (its value sort statically known), or empty. */
    public static Optional<IrExpr.Cast> firstIllegal(IrModule module) {
        InferenceContext ctx = InferenceContext.fromModule(module);
        AssignabilityContext actx = AssignabilityContext.fromModule(module);
        // Declared user coercions, grouped by target base name → the source sorts they accept.
        Map<String, List<IrSort>> coercionSources = new LinkedHashMap<>();
        for (IrStmt s : module.statements()) {
            if (s instanceof IrStmt.Coercion c) {
                String tb = baseName(c.targetSort());
                if (tb != null) {
                    coercionSources.computeIfAbsent(tb, k -> new ArrayList<>()).add(c.sourceSort());
                }
            }
        }
        List<IrExpr.Cast> sink = new ArrayList<>();
        for (IrStmt s : module.statements()) {
            collectFromStatement(s, ctx, actx, coercionSources, sink);
        }
        if (module.main() != null) {
            walk(module.main(), ctx, actx, coercionSources, sink);
        }
        return sink.isEmpty() ? Optional.empty() : Optional.of(sink.get(0));
    }

    /** Whether a {@code (target:value)} cast has any runtime-executable path. */
    private static boolean legal(IrSort target, IrSort valueSort, AssignabilityContext actx,
                                 Map<String, List<IrSort>> coercions) {
        String tb = baseName(target);
        if (tb == null) return true;  // structural / refined target with no nominal head — not gated
        // String target: evalCast always renders and never falls to a user coercion, so legality is
        // exactly "the source is renderable" (a `cast String:(…)` declaration would be unreachable).
        if ("String".equals(tb)) return RENDERABLE.contains(baseName(valueSort));
        // Otherwise: a declared `cast Target:(x:Source)` whose source the value satisfies.
        for (IrSort source : coercions.getOrDefault(tb, List.of())) {
            if (Assignability.isA(valueSort, source, actx)) return true;
        }
        return false;
    }

    // --- statement entry points (mirrors NarrowingInference / CallGate scoping) ---

    private static void collectFromStatement(IrStmt s, InferenceContext ctx, AssignabilityContext actx,
            Map<String, List<IrSort>> coercions, List<IrExpr.Cast> sink) {
        switch (s) {
            case IrStmt.FunctionDecl fd -> walk(fd.body(), seed(ctx, fd.params()), actx, coercions, sink);
            case IrStmt.TraitImpl ti -> {
                for (IrStmt.FunctionDecl m : ti.methods()) {
                    walk(m.body(), seed(ctx, m.params()), actx, coercions, sink);
                }
                for (IrStmt.FunctionDecl a : ti.attributeProducers()) {
                    walk(a.body(), seed(ctx, a.params()), actx, coercions, sink);
                }
            }
            case IrStmt.Coercion c -> walk(c.body(), bind(ctx, c.paramName(), c.sourceSort()), actx, coercions, sink);
            default -> { }  // Proof / Requires / Exports / TypeAlias / NoOp carry no cast sites
        }
    }

    private static InferenceContext seed(InferenceContext ctx, List<IrParam> params) {
        InferenceContext c = ctx;
        for (IrParam p : params) {
            c = bind(c, p.name(), p.sort());
        }
        return c;
    }

    private static InferenceContext bind(InferenceContext ctx, String name, IrSort sort) {
        return sort == null ? ctx : ctx.withVar(name, sort);
    }

    // --- expression walk (scope-threaded) ---

    private static void walk(IrExpr expr, InferenceContext ctx, AssignabilityContext actx,
            Map<String, List<IrSort>> coercions, List<IrExpr.Cast> sink) {
        switch (expr) {
            case IrExpr.Cast cast -> {
                IrSort valueSort = TypeSystem.standard().inferFloor(cast.value(), ctx);
                if (known(valueSort) && !legal(cast.targetSort(), valueSort, actx, coercions)) {
                    sink.add(cast);
                }
                walk(cast.value(), ctx, actx, coercions, sink);
            }
            case IrExpr.LetIn let -> {
                walk(let.value(), ctx, actx, coercions, sink);
                IrSort narrowing = TypeSystem.standard().infer(let.value(), ctx);
                walk(let.body(), bind(ctx, let.name(), narrowing != null ? narrowing : let.declaredSort()),
                        actx, coercions, sink);
            }
            case IrExpr.Match m -> {
                walk(m.scrutinee(), ctx, actx, coercions, sink);
                for (IrExpr.MatchBranch b : m.branches()) {
                    InferenceContext armCtx = m.scrutinee() instanceof IrExpr.Var v
                            && (b.pattern() instanceof IrSort.Refined || b.pattern() instanceof IrSort.Structural)
                            ? bind(ctx, v.name(), b.pattern()) : ctx;
                    walk(b.result(), armCtx, actx, coercions, sink);
                }
            }
            case IrExpr.BinOp op -> {
                walk(op.left(), ctx, actx, coercions, sink);
                walk(op.right(), ctx, actx, coercions, sink);
            }
            case IrExpr.Call c -> {
                for (IrExpr a : c.args()) walk(a, ctx, actx, coercions, sink);
            }
            case IrExpr.Record r -> {
                for (IrExpr member : r.members().values()) walk(member, ctx, actx, coercions, sink);
            }
            case IrExpr.FieldAccess fa -> walk(fa.base(), ctx, actx, coercions, sink);
            case IrExpr.Lambda lam -> walk(lam.body(), seed(ctx, lam.params()), actx, coercions, sink);
            case IrExpr.Apply ap -> {
                walk(ap.fn(), ctx, actx, coercions, sink);
                for (IrExpr a : ap.args()) walk(a, ctx, actx, coercions, sink);
            }
            case IrExpr.MethodCall mc -> {
                walk(mc.receiver(), ctx, actx, coercions, sink);
                for (IrExpr a : mc.args()) walk(a, ctx, actx, coercions, sink);
            }
            case IrExpr.Emit em -> {
                walk(em.event(), ctx, actx, coercions, sink);
                walk(em.body(), ctx, actx, coercions, sink);
            }
            case IrExpr.Iterate it -> {
                walk(it.source(), ctx, actx, coercions, sink);
                for (IrExpr.OutputSpec os : it.outputs()) {
                    if (os.init() != null) walk(os.init(), ctx, actx, coercions, sink);
                }
                for (IrExpr.Arm arm : it.arms()) {
                    InferenceContext armCtx = bind(ctx, it.element(), arm.pattern());
                    for (IrExpr.Write w : arm.writes()) {
                        if (w.key() != null) walk(w.key(), armCtx, actx, coercions, sink);
                        walk(w.value(), armCtx, actx, coercions, sink);
                    }
                }
            }
            // Leaves — no nested expression to walk.
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

    /** Statically-known nominal head (not null, not the unknown {@code _}). */
    private static boolean known(IrSort sort) {
        String b = baseName(sort);
        return b != null && !"_".equals(b);
    }

    private static String baseName(IrSort sort) {
        return switch (sort) {
            case null -> null;
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> r.name();
            case IrSort.Structural s -> s.name();
            case IrSort.Trait t -> t.name();
            default -> null;
        };
    }
}
