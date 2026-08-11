package sibarum.pontif.predicates;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The predicate kernel over the {@code Decimal} (dense, real) domain. Unlike the
 * integer kernel, strict bounds do NOT collapse to a neighbour ({@code @>0} is
 * {@code (0, ∞)}, not {@code [ε, ∞)}), so open/closed endpoints are tracked
 * exactly. These pin the coverage decisions the match-totality checker relies on.
 */
class DecimalDomainTest {

    private static final Sort DEC = Sort.of("Decimal");

    private static SymExpr at(SymExpr.CmpOp op, long k) {
        return SymExpr.cmp(SymExpr.self(), op, SymExpr.lit(k));
    }

    private static boolean total(SymExpr armsUnion, Sort domain) {
        var comp = PredicateArithmetic.complement(armsUnion, domain);
        if (!(comp instanceof ComplementResult.Computed c)) return false;
        return PredicateArithmetic.satisfiable(c.predicate(), domain).isNo();
    }

    @Test void nonzero_coveredBy_strictSplit_isTotal() {
        Sort nonZero = Sort.refined("Decimal", at(SymExpr.CmpOp.NE, 0));
        SymExpr arms = SymExpr.or(at(SymExpr.CmpOp.GT, 0), at(SymExpr.CmpOp.LT, 0));
        assertTrue(total(arms, nonZero), "@>0 | @<0 covers Decimal:@!=0");
    }

    @Test void full_strictSplit_leavesZeroUncovered() {
        SymExpr arms = SymExpr.or(at(SymExpr.CmpOp.GT, 0), at(SymExpr.CmpOp.LT, 0));
        assertTrue(!total(arms, DEC), "@>0 | @<0 must NOT cover all of Decimal (0 is uncovered)");
    }

    @Test void full_closedSplitAtZero_isTotal() {
        SymExpr arms = SymExpr.or(at(SymExpr.CmpOp.GE, 0), at(SymExpr.CmpOp.LT, 0));
        assertTrue(total(arms, DEC), "@>=0 | @<0 covers all of Decimal");
    }

    @Test void full_integerAdjacentSplit_leavesDenseGap() {
        // The dense discriminator: over Int this is total (no integer in (0,1)),
        // over Decimal it is NOT (0.5 is uncovered).
        SymExpr arms = SymExpr.or(at(SymExpr.CmpOp.GE, 1), at(SymExpr.CmpOp.LE, 0));
        assertTrue(!total(arms, DEC), "@>=1 | @<=0 leaves the dense gap (0,1) over Decimal");
    }

    @Test void disjointClaim_isUnsatisfiable() {
        SymExpr both = SymExpr.and(at(SymExpr.CmpOp.EQ, 0), at(SymExpr.CmpOp.GT, 0));
        assertTrue(PredicateArithmetic.satisfiable(both, DEC).isNo(),
                "@==0 & @>0 is disjoint over Decimal");
    }
}
