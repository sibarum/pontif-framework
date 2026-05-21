package sibarum.pontif.core.symbolic;

import java.util.List;

public final class SignAnalysis {

    private SignAnalysis() {}

    public static Sign computeSign(SymExpr expr, List<SymExpr> hypotheses) {
        return switch (expr) {
            case SymExpr.Lit(long v) -> signOfLong(v);
            case SymExpr.Frac(long n, long d) -> signOfLong(n);
            case SymExpr.Self s -> signFromHypotheses(s, hypotheses);
            case SymExpr.Var v -> signFromHypotheses(v, hypotheses);
            case SymExpr.Add(SymExpr l, SymExpr r) ->
                    computeSign(l, hypotheses).add(computeSign(r, hypotheses));
            case SymExpr.Mul(SymExpr l, SymExpr r) -> {
                if (l.equals(r)) {
                    // x * x is structurally a square — non-negative for any base
                    yield squareSign(computeSign(l, hypotheses));
                }
                yield computeSign(l, hypotheses).multiply(computeSign(r, hypotheses));
            }
            case SymExpr.Pow(SymExpr b, SymExpr e) -> computePowSign(b, e, hypotheses);
            default -> Sign.TOP;
        };
    }

    public static boolean canDischarge(List<SymExpr> hypotheses, SymExpr goal) {
        if (!(goal instanceof SymExpr.Cmp(SymExpr subject, SymExpr.CmpOp op, SymExpr bound))) {
            return false;
        }
        Long boundLong = asLongConst(bound);
        if (boundLong == null) return false;
        Sign s = computeSign(subject, hypotheses);
        return s.satisfies(op, boundLong);
    }

    private static Sign signOfLong(long v) {
        if (v > 0) return Sign.POSITIVE;
        if (v < 0) return Sign.NEGATIVE;
        return Sign.ZERO;
    }

    private static Sign signFromHypotheses(SymExpr subject, List<SymExpr> hypotheses) {
        Sign result = Sign.TOP;
        for (SymExpr fact : hypotheses) {
            if (!(fact instanceof SymExpr.Cmp(SymExpr s, SymExpr.CmpOp op, SymExpr b))) continue;
            if (!s.equals(subject)) continue;
            Long bound = asLongConst(b);
            if (bound == null) continue;
            result = result.meet(signFromCmpBound(op, bound));
        }
        return result;
    }

    private static Sign signFromCmpBound(SymExpr.CmpOp op, long bound) {
        return switch (op) {
            case GT -> bound >= 0 ? Sign.POSITIVE : Sign.TOP;
            case GE -> bound > 0 ? Sign.POSITIVE : (bound == 0 ? Sign.NON_NEGATIVE : Sign.TOP);
            case LT -> bound <= 0 ? Sign.NEGATIVE : Sign.TOP;
            case LE -> bound < 0 ? Sign.NEGATIVE : (bound == 0 ? Sign.NON_POSITIVE : Sign.TOP);
            case EQ -> {
                if (bound > 0) yield Sign.POSITIVE;
                if (bound < 0) yield Sign.NEGATIVE;
                yield Sign.ZERO;
            }
            case NE -> Sign.TOP;
        };
    }

    private static Sign computePowSign(SymExpr base, SymExpr exp, List<SymExpr> hypotheses) {
        Long expLong = asLongConst(exp);
        if (expLong == null) return Sign.TOP;
        if (expLong == 0) return Sign.POSITIVE;
        if (expLong < 0) return Sign.TOP;
        Sign baseSign = computeSign(base, hypotheses);
        if (expLong % 2 == 0) {
            return squareSign(baseSign);
        }
        return baseSign;
    }

    private static Sign squareSign(Sign baseSign) {
        return switch (baseSign) {
            case ZERO -> Sign.ZERO;
            case POSITIVE, NEGATIVE -> Sign.POSITIVE;
            case NON_NEGATIVE, NON_POSITIVE, TOP -> Sign.NON_NEGATIVE;
            case BOTTOM -> Sign.BOTTOM;
        };
    }

    private static Long asLongConst(SymExpr expr) {
        if (expr instanceof SymExpr.Lit l) return l.value();
        if (expr instanceof SymExpr.Frac f && f.denom() == 1) return f.num();
        return null;
    }
}
