package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;

import java.util.List;

/**
 * The <b>effective-sort gradient</b> of a self-recursive function — the
 * direction its parameters' effective sorts move under repeated iteration
 * (docs/receipt-graph-overhaul.md, Step 3). Reads the receipt graph directly:
 * a back-reference {@link CallRef} (one whose target is the enclosing function)
 * carries the recursive argument as an expression in the parameters, so the
 * per-parameter <em>step</em> is right there in the graph.
 *
 * <p>Three coherent directions, plus a "not recursive" case:
 * <ul>
 *   <li><b>CONVERGING</b> — a parameter moves monotonically <em>toward</em> a
 *       declared bound (decreasing while bounded below, or increasing while
 *       bounded above). This is well-founded descent: the recursion approaches
 *       the region where the base case fires. The gradient's read of
 *       termination — the arithmetic descent {@code NoHalt} explicitly cannot
 *       prove.</li>
 *   <li><b>DIVERGING</b> — a parameter's magnitude grows with no bound in the
 *       direction of travel, and none converges. Non-termination by growth.</li>
 *   <li><b>WANDERING</b> — stationary (verbatim re-entry — {@code NoHalt}'s
 *       divergence witness), non-additive (geometric, resetting), or
 *       inconsistent across recursive calls: no coherent gradient the engine
 *       will commit to.</li>
 * </ul>
 *
 * <p><b>A gradient, not a proof.</b> This is a direction-of-change read, per the
 * design's framing — the sound termination/divergence verdicts stay with the
 * receipt graph's inductive discharge and {@code NoHalt}. Slice 1 handles the
 * additive-linear single-step shape ({@code f(n-1)}, {@code f(n+1)},
 * {@code f(n)}); anything else reads as WANDERING (honest abstention).
 */
public final class GradientAnalysis {

    private GradientAnalysis() {}

    public enum Gradient { CONVERGING, DIVERGING, WANDERING, NON_RECURSIVE }

    public record Result(Gradient gradient, String detail) {}

    /** The gradient of {@code node} over its self-recursive back-references. */
    public static Result of(Node node) {
        List<CallRef> recursive = node.branches().stream()
                .flatMap(b -> b.calls().stream())
                .filter(c -> c.targetFunctionName().equals(node.functionName()))
                .toList();
        if (recursive.isEmpty()) {
            return new Result(Gradient.NON_RECURSIVE, "not self-recursive");
        }

        Gradient divergingFallback = null;
        String divergingDetail = null;
        boolean anyStationary = false;
        boolean anyUsable = false;

        List<Param> params = node.params();
        for (int i = 0; i < params.size(); i++) {
            Long step = consistentAdditiveStep(recursive, i, params.get(i).name());
            if (step == null) {
                continue;  // this parameter is not a clean additive measure
            }
            anyUsable = true;
            if (step == 0) {
                anyStationary = true;
                continue;
            }
            Bounds b = boundsOf(params.get(i).sort());
            if (step < 0) {
                if (b.below) {
                    return new Result(Gradient.CONVERGING,
                            params.get(i).name() + " decreases by " + (-step)
                                    + " toward its lower bound");
                }
                divergingFallback = Gradient.DIVERGING;
                divergingDetail = params.get(i).name() + " decreases by " + (-step)
                        + " with no lower bound";
            } else {
                if (b.above) {
                    return new Result(Gradient.CONVERGING,
                            params.get(i).name() + " increases by " + step
                                    + " toward its upper bound");
                }
                divergingFallback = Gradient.DIVERGING;
                divergingDetail = params.get(i).name() + " grows by " + step + " without bound";
            }
        }

        if (divergingFallback != null) {
            return new Result(divergingFallback, divergingDetail);
        }
        if (anyStationary) {
            return new Result(Gradient.WANDERING,
                    "stationary recursion (no progress; NoHalt proves non-halting)");
        }
        return new Result(Gradient.WANDERING,
                anyUsable ? "no coherent gradient" : "recursion step is not additive-linear");
    }

    /**
     * The additive step {@code b} on parameter {@code i} (arg = param + b), if
     * every recursive call agrees on the same constant {@code b} and the arg is
     * exactly {@code param + b} (coefficient 1). {@code null} otherwise (a
     * coefficient ≠ 1, another variable, a non-linear term, or disagreement).
     */
    private static Long consistentAdditiveStep(List<CallRef> recursive, int i, String paramVar) {
        Long agreed = null;
        for (CallRef c : recursive) {
            if (i >= c.argBindings().size()) {
                return null;
            }
            long[] lin = linearForm(c.argBindings().get(i), paramVar);
            if (lin == null || lin[0] != 1) {
                return null;  // not `1·param + b`
            }
            if (agreed == null) {
                agreed = lin[1];
            } else if (agreed != lin[1]) {
                return null;  // recursive calls disagree on the step
            }
        }
        return agreed;
    }

    /**
     * Linear form {@code [a, b]} of {@code e} as {@code a·var + b}, or
     * {@code null} if {@code e} is not linear in {@code var} alone (mentions
     * another variable, multiplies two non-constants, or uses a form outside
     * the additive-linear fragment). Slice-1 integer fragment: Lit, the target
     * Var, Add, and Mul-by-constant.
     */
    private static long[] linearForm(SymExpr e, String var) {
        return switch (e) {
            case SymExpr.Lit(long v) -> new long[]{0, v};
            case SymExpr.Var(String name) ->
                    name.equals(var) ? new long[]{1, 0} : null;  // another variable → abstain
            case SymExpr.Add(SymExpr l, SymExpr r) -> {
                long[] lf = linearForm(l, var);
                long[] rf = linearForm(r, var);
                yield (lf == null || rf == null) ? null : new long[]{lf[0] + rf[0], lf[1] + rf[1]};
            }
            case SymExpr.Mul(SymExpr l, SymExpr r) -> {
                long[] lf = linearForm(l, var);
                long[] rf = linearForm(r, var);
                if (lf == null || rf == null) yield null;
                if (lf[0] == 0) yield new long[]{lf[1] * rf[0], lf[1] * rf[1]};   // const · expr
                if (rf[0] == 0) yield new long[]{rf[1] * lf[0], rf[1] * lf[1]};   // expr · const
                yield null;                                                       // var · var → non-linear
            }
            default -> null;
        };
    }

    private record Bounds(boolean below, boolean above) {}

    /** Whether the parameter's refinement bounds it below and/or above (on {@code @}). */
    private static Bounds boundsOf(Sort sort) {
        if (!sort.isRefined()) {
            return new Bounds(false, false);
        }
        boolean[] bd = {false, false};  // {below, above}
        scanBounds(sort.predicate(), bd);
        return new Bounds(bd[0], bd[1]);
    }

    private static void scanBounds(SymExpr pred, boolean[] bd) {
        switch (pred) {
            case SymExpr.And(SymExpr l, SymExpr r) -> { scanBounds(l, bd); scanBounds(r, bd); }
            case SymExpr.Cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r) -> {
                boolean selfLeft = l instanceof SymExpr.Self;
                boolean selfRight = r instanceof SymExpr.Self;
                if (selfLeft == selfRight) {
                    return;  // need @ on exactly one side against a bound term
                }
                // Normalize so the comparison reads @ OP term.
                SymExpr.CmpOp o = selfLeft ? op : flip(op);
                switch (o) {
                    case GE, GT -> bd[0] = true;               // @ >= / > c  → bounded below
                    case LE, LT -> bd[1] = true;               // @ <= / < c  → bounded above
                    case EQ -> { bd[0] = true; bd[1] = true; } // @ == c      → both
                    case NE -> { }
                }
            }
            default -> { }  // Or / other: no bound guarantee
        }
    }

    private static SymExpr.CmpOp flip(SymExpr.CmpOp op) {
        return switch (op) {
            case LT -> SymExpr.CmpOp.GT;
            case LE -> SymExpr.CmpOp.GE;
            case GT -> SymExpr.CmpOp.LT;
            case GE -> SymExpr.CmpOp.LE;
            case EQ -> SymExpr.CmpOp.EQ;
            case NE -> SymExpr.CmpOp.NE;
        };
    }
}
