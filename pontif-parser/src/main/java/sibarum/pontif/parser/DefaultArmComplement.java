package sibarum.pontif.parser;

import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.predicates.ComplementResult;
import sibarum.pontif.predicates.PredicateArithmetic;

import java.util.List;

import static sibarum.pontif.parser.IrQueries.baseSortName;

/**
 * Desugars a match's {@code _} default arm into an explicit refined pattern.
 *
 * The pattern is the complement of the union of the explicit arms' predicates,
 * taken over the scrutinee's sort. This is the parser's one call into the
 * predicate-arithmetic engine, isolated here so the recursive-descent parser
 * does not depend on the type-system reasoning it drives - the parser supplies
 * the already-resolved scrutinee sort and receives the desugared pattern back.
 *
 * The result is in IR form so the IR sees only explicit predicates: the
 * {@code _} is fully desugared by the time it leaves the parser.
 */
final class DefaultArmComplement {

    private DefaultArmComplement() {
    }

    /**
     * The refined pattern for a {@code _} default arm, or the universal pattern
     * {@code [_]} where the precise complement is not computable. Ordered match
     * makes the universal fallback total by construction (the arm catches
     * exactly what earlier arms did not); the precise complement is kept where
     * the kernel can compute it, because it gives the arm body an exact
     * narrowing rather than {@code _}.
     *
     * @param scrutineeSort the scrutinee's resolved sort, or null when unknown
     */
    static IrSort compute(
            IrSort scrutineeSort,
            List<IrExpr.MatchBranch> branches,
            int defaultArmIndex,
            Origin defaultArmOrigin) {
        IrSort universal = new IrSort.Named("_", defaultArmOrigin);
        if (scrutineeSort == null) {
            return universal;
        }

        // Union the explicit arms' predicates as SymExpr.
        SymExpr unionPredicate = null;
        for (int i = 0; i < branches.size(); i++) {
            if (i == defaultArmIndex) continue;
            IrSort armPattern = branches.get(i).pattern();
            if (!(armPattern instanceof IrSort.Refined refined)) {
                return universal;  // destructure / bare arms - no predicate to complement
            }
            SymExpr armPred;
            try {
                armPred = IrCompiler.compileSymExpr(refined.predicate());
            } catch (CompileException ce) {
                return universal;
            }
            unionPredicate = (unionPredicate == null) ? armPred : SymExpr.or(unionPredicate, armPred);
        }
        // No explicit arms - complement of false = entire domain.
        if (unionPredicate == null) unionPredicate = SymExpr.bool(false);

        Sort domain;
        try {
            domain = IrCompiler.compileSort(scrutineeSort);
        } catch (CompileException ce) {
            return universal;
        }

        ComplementResult complement = PredicateArithmetic.complement(unionPredicate, domain);
        if (complement instanceof ComplementResult.Unknown) {
            return universal;  // outside the decidable fragment - order does the work
        }
        SymExpr complementSym = ((ComplementResult.Computed) complement).predicate();
        IrExpr complementIr = symExprToIrExpr(complementSym, defaultArmOrigin);

        return new IrSort.Refined(baseSortName(scrutineeSort), complementIr, defaultArmOrigin);
    }

    /**
     * Converts a SymExpr back to an IrExpr, for the subset of shapes produced by
     * PredicateArithmetic.complement (Bool, Lit, Self, Cmp of those, And, Or).
     * Anything outside that subset is a framework bug - the complement result
     * should always stay within the Int-comparison fragment.
     */
    private static IrExpr symExprToIrExpr(SymExpr expr, Origin origin) {
        return switch (expr) {
            case SymExpr.Bool b -> new IrExpr.Bool(b.value(), origin);
            case SymExpr.Lit l -> new IrExpr.Lit(l.value(), origin);
            case SymExpr.Dec d -> new IrExpr.Dec(d.value(), origin);
            case SymExpr.Self s -> new IrExpr.SelfRef(origin);
            case SymExpr.Cmp(SymExpr left, SymExpr.CmpOp op, SymExpr right) ->
                    new IrExpr.BinOp(cmpOpToIrOp(op),
                            symExprToIrExpr(left, origin),
                            symExprToIrExpr(right, origin),
                            origin);
            case SymExpr.And(SymExpr l, SymExpr r) ->
                    new IrExpr.BinOp(IrExpr.Op.AND,
                            symExprToIrExpr(l, origin),
                            symExprToIrExpr(r, origin),
                            origin);
            case SymExpr.Or(SymExpr l, SymExpr r) ->
                    new IrExpr.BinOp(IrExpr.Op.OR,
                            symExprToIrExpr(l, origin),
                            symExprToIrExpr(r, origin),
                            origin);
            default -> throw new IllegalStateException(
                    "Unexpected SymExpr in complement result (outside Int-comparison fragment): " + expr);
        };
    }

    private static IrExpr.Op cmpOpToIrOp(SymExpr.CmpOp op) {
        return switch (op) {
            case LT -> IrExpr.Op.LT;
            case LE -> IrExpr.Op.LE;
            case GT -> IrExpr.Op.GT;
            case GE -> IrExpr.Op.GE;
            case EQ -> IrExpr.Op.EQ;
            case NE -> IrExpr.Op.NE;
        };
    }
}
