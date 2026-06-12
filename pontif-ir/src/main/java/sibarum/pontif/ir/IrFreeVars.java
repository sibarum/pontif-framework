package sibarum.pontif.ir;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

public final class IrFreeVars {

    private IrFreeVars() {}

    public static LinkedHashSet<String> freeVars(IrExpr expr) {
        LinkedHashSet<String> free = new LinkedHashSet<>();
        collect(expr, new HashSet<>(), free);
        return free;
    }

    private static void collect(IrExpr expr, Set<String> bound, LinkedHashSet<String> free) {
        switch (expr) {
            case IrExpr.Lit l -> {}
            case IrExpr.Dec d -> {}
            case IrExpr.Chr c -> {}
            case IrExpr.Str s -> {}
            case IrExpr.Bool b -> {}
            case IrExpr.SelfRef s -> {}
            case IrExpr.DispatchRef d -> {}
            case IrExpr.Var v -> {
                if (!bound.contains(v.name())) {
                    free.add(v.name());
                }
            }
            case IrExpr.BinOp op -> {
                collect(op.left(), bound, free);
                collect(op.right(), bound, free);
            }
            case IrExpr.LetIn l -> {
                collect(l.value(), bound, free);
                Set<String> extended = new HashSet<>(bound);
                extended.add(l.name());
                collect(l.body(), extended, free);
            }
            case IrExpr.Call c -> {
                for (IrExpr arg : c.args()) {
                    collect(arg, bound, free);
                }
            }
            case IrExpr.Lambda lam -> {
                Set<String> extended = new HashSet<>(bound);
                for (IrParam p : lam.params()) {
                    extended.add(p.name());
                }
                collect(lam.body(), extended, free);
            }
            case IrExpr.Apply app -> {
                collect(app.fn(), bound, free);
                for (IrExpr a : app.args()) {
                    collect(a, bound, free);
                }
            }
            case IrExpr.Match m -> {
                collect(m.scrutinee(), bound, free);
                for (IrExpr.MatchBranch b : m.branches()) {
                    collect(b.result(), bound, free);
                }
            }
            case IrExpr.Record r -> {
                for (IrExpr v : r.members().values()) {
                    collect(v, bound, free);
                }
            }
            case IrExpr.FieldAccess fa -> collect(fa.base(), bound, free);
        }
    }
}
