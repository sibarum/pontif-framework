package sibarum.pontif.demo.symbolic.algebra;

import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.Algebra;
import sibarum.pontif.core.symbolic.algebra.BasisElement;
import sibarum.pontif.core.symbolic.ArithmeticRules;
import sibarum.pontif.core.symbolic.TotalExpressionRules;

import java.util.ArrayList;
import java.util.List;

public final class Algebras {

    private Algebras() {}

    private static final SymExpr HALF = SymExpr.frac(1, 2);

    public static final SymExpr ONE     = SymExpr.lit(1);
    public static final SymExpr EPSILON = SymExpr.pow(SymExpr.lit(0), HALF);
    public static final SymExpr I       = SymExpr.pow(SymExpr.lit(-1), HALF);
    public static final SymExpr J       = SymExpr.pow(SymExpr.lit(1), HALF);

    public static Simplifier combinedSimplifier() {
        List<RewriteRule> rules = new ArrayList<>();
        rules.addAll(TotalExpressionRules.all());
        rules.addAll(ArithmeticRules.all());
        return new Simplifier(rules);
    }

    public static Algebra dualNumbers() {
        return new Algebra(
                "DualNumbers",
                List.of(
                        new BasisElement("1", ONE),
                        new BasisElement("ε", EPSILON)),
                "1",
                combinedSimplifier());
    }

    public static Algebra complex() {
        return new Algebra(
                "Complex",
                List.of(
                        new BasisElement("1", ONE),
                        new BasisElement("i", I)),
                "1",
                combinedSimplifier());
    }

    public static Algebra splitComplex() {
        return new Algebra(
                "SplitComplex",
                List.of(
                        new BasisElement("1", ONE),
                        new BasisElement("j", J)),
                "1",
                combinedSimplifier());
    }
}
