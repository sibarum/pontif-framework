package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.types.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrCallInPredicateTest {

    // --- Shape of the lifted Call ---

    @Test
    void noArgCall_compilesToBareVar() {
        IrExpr call = IrExpr.call("nothing", List.of());
        SymExpr sym = IrCompiler.compileSymExpr(call);
        assertEquals(SymExpr.var("nothing"), sym);
    }

    @Test
    void oneArgCall_compilesToAppOfVarAndArg() {
        IrExpr call = IrExpr.call("isEven", List.of(IrExpr.lit(4)));
        SymExpr sym = IrCompiler.compileSymExpr(call);
        SymExpr expected = SymExpr.app(SymExpr.var("isEven"), SymExpr.lit(4));
        assertEquals(expected, sym);
    }

    @Test
    void multiArgCall_compilesToLeftFoldOfApp() {
        // gcd(12, 18) → App(App(Var("gcd"), Lit(12)), Lit(18))
        IrExpr call = IrExpr.call("gcd", List.of(IrExpr.lit(12), IrExpr.lit(18)));
        SymExpr sym = IrCompiler.compileSymExpr(call);
        SymExpr expected = SymExpr.app(
                SymExpr.app(SymExpr.var("gcd"), SymExpr.lit(12)),
                SymExpr.lit(18));
        assertEquals(expected, sym);
    }

    @Test
    void callOverSelf_compilesToAppOverSelf() {
        // isPrime(self) → App(Var("isPrime"), Self)
        IrExpr call = IrExpr.call("isPrime", List.of(IrExpr.self()));
        SymExpr sym = IrCompiler.compileSymExpr(call);
        SymExpr expected = SymExpr.app(SymExpr.var("isPrime"), SymExpr.self());
        assertEquals(expected, sym);
    }

    // --- Refined sorts compile end-to-end when their predicate uses Call ---

    @Test
    void refinedSort_withCallInPredicate_compilesWithoutException() {
        // Int[isPrime(self)]
        IrSort sort = IrSort.refined("Int",
                IrExpr.call("isPrime", List.of(IrExpr.self())));

        Sort compiled = IrCompiler.compileSort(sort);

        assertTrue(compiled.isRefined());
        SymExpr expected = SymExpr.app(SymExpr.var("isPrime"), SymExpr.self());
        assertEquals(expected, compiled.predicate());
    }

    @Test
    void refinedSort_compositeCallPredicate_carriesAppFoldIntoSort() {
        // Int[gcd(self, 6) == 1]   (coprime-with-6)
        IrSort sort = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.EQ,
                        IrExpr.call("gcd", List.of(IrExpr.self(), IrExpr.lit(6))),
                        IrExpr.lit(1)));

        Sort compiled = IrCompiler.compileSort(sort);

        assertTrue(compiled.isRefined());
        SymExpr expectedGcd = SymExpr.app(
                SymExpr.app(SymExpr.var("gcd"), SymExpr.self()),
                SymExpr.lit(6));
        SymExpr expectedPredicate = SymExpr.cmp(expectedGcd, SymExpr.CmpOp.EQ, SymExpr.lit(1));
        assertEquals(expectedPredicate, compiled.predicate());
    }

    // --- End-to-end: with a custom simplifier rule, the predicate actually reduces ---

    @Test
    void refinedSort_predicateReducesWithCustomFunctionRule_passes() {
        // twice(n) = 2 * n     (provided as a rewrite rule)
        // sort: Int[twice(self) > 10]
        // value: 6  →  twice(6) = 12 > 10 → true
        IrSort sort = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT,
                        IrExpr.call("twice", List.of(IrExpr.self())),
                        IrExpr.lit(10)));
        Sort compiled = IrCompiler.compileSort(sort);

        Simplifier simp = new Simplifier(rulesWithTwiceAndCmp());
        ProofResult result = Refinements.satisfies(SymExpr.lit(6), compiled, simp);

        assertInstanceOf(ProofResult.Passed.class, result,
                "expected Passed; got " + result);
    }

    @Test
    void refinedSort_predicateReducesWithCustomFunctionRule_fails() {
        // Same sort; value 4 → twice(4) = 8 > 10 → false
        IrSort sort = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT,
                        IrExpr.call("twice", List.of(IrExpr.self())),
                        IrExpr.lit(10)));
        Sort compiled = IrCompiler.compileSort(sort);

        Simplifier simp = new Simplifier(rulesWithTwiceAndCmp());
        ProofResult result = Refinements.satisfies(SymExpr.lit(4), compiled, simp);

        assertInstanceOf(ProofResult.Failed.class, result,
                "expected Failed; got " + result);
    }

    @Test
    void refinedSort_predicateWithUnreducedCall_yieldsResidual() {
        // Sort: Int[isPrime(self)]; no rule provided, value is concrete.
        // Without a rule for isPrime, the predicate cannot reduce to Bool — it stays
        // residual. This documents the contract: representability does not imply
        // reducibility; rules are user-extensible.
        IrSort sort = IrSort.refined("Int",
                IrExpr.call("isPrime", List.of(IrExpr.self())));
        Sort compiled = IrCompiler.compileSort(sort);

        Simplifier simp = new Simplifier(List.of());
        ProofResult result = Refinements.satisfies(SymExpr.lit(7), compiled, simp);

        assertInstanceOf(ProofResult.Residual.class, result,
                "expected Residual when no rule discharges the call; got " + result);
    }

    // --- Helpers ---

    private static List<RewriteRule> rulesWithTwiceAndCmp() {
        List<RewriteRule> rules = new ArrayList<>();
        rules.add(twiceRule());
        rules.add(cmpLitLit());
        return rules;
    }

    /** App(Var("twice"), Lit(n)) → Lit(2*n) */
    private static RewriteRule twiceRule() {
        return (expr, simp) -> {
            if (expr instanceof SymExpr.App(SymExpr.Var(String name), SymExpr.Lit lit)
                    && name.equals("twice")) {
                return Optional.of(SymExpr.lit(2 * lit.value()));
            }
            return Optional.empty();
        };
    }

    private static RewriteRule cmpLitLit() {
        return (expr, simp) -> {
            if (expr instanceof SymExpr.Cmp(SymExpr.Lit l, SymExpr.CmpOp op, SymExpr.Lit r)) {
                boolean truth = switch (op) {
                    case LT -> l.value() < r.value();
                    case LE -> l.value() <= r.value();
                    case GT -> l.value() > r.value();
                    case GE -> l.value() >= r.value();
                    case EQ -> l.value() == r.value();
                    case NE -> l.value() != r.value();
                };
                return Optional.of(SymExpr.bool(truth));
            }
            return Optional.empty();
        };
    }
}
