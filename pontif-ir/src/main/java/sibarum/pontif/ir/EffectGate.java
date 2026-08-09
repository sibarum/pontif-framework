package sibarum.pontif.ir;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The <b>effect gate</b> (docs/orchestration.md, the {@code let}-led preamble, cut 1b) — the second consumer
 * of {@link EmitInterface}. It rejects a <b>discard</b> ({@code let EXPR} with no {@code =}, lowered to a
 * {@code #discard#}-named {@link IrExpr.LetIn}) whose {@code EXPR} is <b>provably effect-free</b>: discarding
 * the value of an expression that can have no effect is meaningless — a typo for {@code let x = …}, or a
 * misunderstanding of what the callee does. The author is pointed at {@code let x = …} (bind) or
 * {@code let _ = …} (intentional throwaway).
 *
 * <h2>Soundness: prove purity, fail open</h2>
 * The gate fires only when it can <b>prove</b> the discarded expression pure — never on a doubt. Purity is a
 * monotone under-approximation computed as a fixpoint over the program's user functions: a function is pure
 * iff its body neither {@code emit}s nor calls anything not-provably-pure; a call to a native, a method, a
 * dynamic {@code Apply}, or any unresolved name is treated as <b>possibly effectful</b> (native effect sinks
 * like {@code StdOut}/{@code present} are already {@code emit}s and caught directly). So the gate may miss a
 * pure-but-unclassified discard (a discard of a pure native result slips through) but it can never forbid a
 * legitimate effectful one — the fail-open direction ratified for cut 1b.
 */
public final class EffectGate {

    private EffectGate() {}

    /**
     * Rejects {@code module} with a {@link CompileException} at the first discard of a provably effect-free
     * expression. A no-op for a program with no discards, or whose every discard drives a (possible) effect.
     */
    public static void check(IrModule module) throws CompileException {
        Set<String> pure = pureFunctions(module);
        for (IrStmt s : module.statements()) {
            if (s instanceof IrStmt.FunctionDecl fd) scan(fd.body(), pure);
        }
        if (module.main() != null) scan(module.main(), pure);
    }

    /**
     * The set of user-function names (raw and bare) provably pure, by monotone fixpoint. Every function
     * starts an optimistic pure candidate; a function whose body is not {@link #pure} against the current
     * estimate is struck, and the pass repeats until the set stabilizes (purity only ever goes true→false,
     * so it converges). Recursion is pure by construction — a self-call reads the candidate as pure.
     */
    private static Set<String> pureFunctions(IrModule module) {
        Map<String, IrExpr> bodies = new LinkedHashMap<>();
        for (IrStmt s : module.statements()) {
            if (s instanceof IrStmt.FunctionDecl fd) bodies.put(fd.name(), fd.body());
        }
        Set<String> pure = new LinkedHashSet<>();
        for (String name : bodies.keySet()) {
            pure.add(name);
            pure.add(bare(name));
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, IrExpr> e : bodies.entrySet()) {
                if (!pure.contains(e.getKey())) continue;
                if (!pure(e.getValue(), pure)) {
                    pure.remove(e.getKey());
                    pure.remove(bare(e.getKey()));
                    changed = true;
                }
            }
        }
        return pure;
    }

    /**
     * Is {@code expr} provably effect-free against the current set of proven-pure function names? An
     * exhaustive switch over the sealed {@link IrExpr} — the compiler forces a case per node kind, so a new
     * node can never silently slip past as "pure" (the same discipline as {@link EmitInterface}). Nodes whose
     * effect cannot be established structurally — {@link IrExpr.Emit}, a {@link IrExpr.MethodCall}, a dynamic
     * {@link IrExpr.Apply}, an {@link IrExpr.Iterate}, or a {@link IrExpr.Call} to a name not in {@code pure} —
     * return {@code false} (possibly effectful), the fail-open direction.
     */
    private static boolean pure(IrExpr expr, Set<String> pure) {
        return switch (expr) {
            case IrExpr.Lit l -> true;
            case IrExpr.Dec d -> true;
            case IrExpr.Chr c -> true;
            case IrExpr.Str s -> true;
            case IrExpr.Bool b -> true;
            case IrExpr.Var v -> true;
            case IrExpr.SelfRef s -> true;
            case IrExpr.DispatchRef d -> true;                 // a reference is pure; invoking it is Apply/Call
            case IrExpr.Lambda lam -> true;                    // defining a lambda runs nothing
            case IrExpr.BinOp op -> pure(op.left(), pure) && pure(op.right(), pure);
            case IrExpr.Cast cast -> pure(cast.value(), pure);
            case IrExpr.FieldAccess fa -> pure(fa.base(), pure);
            case IrExpr.LetIn l -> pure(l.value(), pure) && pure(l.body(), pure);
            case IrExpr.Record r -> r.members().values().stream().allMatch(v -> pure(v, pure));
            case IrExpr.Match m -> pure(m.scrutinee(), pure)
                    && m.branches().stream().allMatch(b -> pure(b.result(), pure));
            case IrExpr.Call c -> (pure.contains(c.functionName()) || pure.contains(bare(c.functionName())))
                    && c.args().stream().allMatch(a -> pure(a, pure));
            // Not structurally provable — fail open (treat as possibly effectful).
            case IrExpr.Emit em -> false;
            case IrExpr.MethodCall mc -> false;
            case IrExpr.Apply app -> false;
            case IrExpr.Iterate it -> false;
        };
    }

    /**
     * Walk {@code expr}, throwing at the first {@code #discard#}-named {@link IrExpr.LetIn} whose value is
     * {@link #pure}. Exhaustive so no discard-bearing sub-tree is skipped; recurses through every child
     * (a discard can nest anywhere — inside an arm, an argument, another discard's continuation).
     */
    private static void scan(IrExpr expr, Set<String> pure) throws CompileException {
        switch (expr) {
            case IrExpr.Lit l -> {}
            case IrExpr.Dec d -> {}
            case IrExpr.Chr c -> {}
            case IrExpr.Str s -> {}
            case IrExpr.Bool b -> {}
            case IrExpr.Var v -> {}
            case IrExpr.SelfRef s -> {}
            case IrExpr.DispatchRef d -> {}
            case IrExpr.BinOp op -> { scan(op.left(), pure); scan(op.right(), pure); }
            case IrExpr.Cast cast -> scan(cast.value(), pure);
            case IrExpr.FieldAccess fa -> scan(fa.base(), pure);
            case IrExpr.Lambda lam -> scan(lam.body(), pure);
            case IrExpr.LetIn l -> {
                if (l.name().startsWith("#discard#") && pure(l.value(), pure)) {
                    throw new CompileException(
                            "this expression has no effect, so discarding its value with `let …` does "
                                    + "nothing — bind it with `let x = …`, or discard it intentionally with "
                                    + "`let _ = …`.",
                            l.origin());
                }
                scan(l.value(), pure);
                scan(l.body(), pure);
            }
            case IrExpr.Call c -> { for (IrExpr a : c.args()) scan(a, pure); }
            case IrExpr.Apply app -> { scan(app.fn(), pure); for (IrExpr a : app.args()) scan(a, pure); }
            case IrExpr.MethodCall mc -> { scan(mc.receiver(), pure); for (IrExpr a : mc.args()) scan(a, pure); }
            case IrExpr.Record r -> { for (IrExpr v : r.members().values()) scan(v, pure); }
            case IrExpr.Emit em -> { scan(em.event(), pure); scan(em.body(), pure); }
            case IrExpr.Match m -> {
                scan(m.scrutinee(), pure);
                for (IrExpr.MatchBranch b : m.branches()) scan(b.result(), pure);
            }
            case IrExpr.Iterate it -> {
                scan(it.source(), pure);
                for (IrExpr cs : it.coSources()) scan(cs, pure);
                for (IrExpr.OutputSpec os : it.outputs()) if (os.init() != null) scan(os.init(), pure);
                for (IrExpr.Arm arm : it.arms()) {
                    for (IrExpr.Write w : arm.writes()) {
                        if (w.key() != null) scan(w.key(), pure);
                        scan(w.value(), pure);
                    }
                }
            }
        }
    }

    /** The bare (module-path-stripped) name, matching how calls key against declarations. */
    private static String bare(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }
}
