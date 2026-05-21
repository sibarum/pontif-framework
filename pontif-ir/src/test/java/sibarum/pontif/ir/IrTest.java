package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.symbolic.Simplifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IrTest {

    private static List<RewriteRule> defaultRules() {
        List<RewriteRule> rules = new ArrayList<>();
        // Minimal in-IR rule set: constant comparison folding, since refinement predicates
        // are compiled to SymExpr and need to fold constants for satisfaction checks.
        rules.add(cmpLitLit());
        return rules;
    }

    private static RewriteRule cmpLitLit() {
        return (expr, simp) -> {
            if (expr instanceof sibarum.pontif.core.symbolic.SymExpr.Cmp(
                    sibarum.pontif.core.symbolic.SymExpr.Lit l,
                    sibarum.pontif.core.symbolic.SymExpr.CmpOp op,
                    sibarum.pontif.core.symbolic.SymExpr.Lit r)) {
                boolean truth = switch (op) {
                    case LT -> l.value() < r.value();
                    case LE -> l.value() <= r.value();
                    case GT -> l.value() > r.value();
                    case GE -> l.value() >= r.value();
                    case EQ -> l.value() == r.value();
                    case NE -> l.value() != r.value();
                };
                return Optional.of(sibarum.pontif.core.symbolic.SymExpr.bool(truth));
            }
            return Optional.empty();
        };
    }

    private static Simplifier defaultSimplifier() {
        return new Simplifier(defaultRules());
    }

    // --- Trivial: literals and arithmetic ---

    @Test
    void evaluatesLiteral() {
        IrModule module = new IrModule("trivial", List.of(), IrExpr.lit(42));
        CompiledModule compiled = new IrCompiler(defaultSimplifier()).compile(module);
        assertEquals(42L, new IrInterpreter(defaultSimplifier()).eval(compiled));
    }

    @Test
    void evaluatesArithmetic() {
        IrModule module = new IrModule("arith", List.of(),
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.lit(2), IrExpr.lit(3)));
        CompiledModule compiled = new IrCompiler(defaultSimplifier()).compile(module);
        assertEquals(5L, new IrInterpreter(defaultSimplifier()).eval(compiled));
    }

    @Test
    void evaluatesLetIn() {
        // let x = 7 in x * x
        IrExpr expr = IrExpr.letIn(
                "x", IrSort.named("Int"), IrExpr.lit(7),
                IrExpr.binOp(IrExpr.Op.MUL, IrExpr.var("x"), IrExpr.var("x")));
        IrModule module = new IrModule("letdemo", List.of(), expr);
        CompiledModule compiled = new IrCompiler(defaultSimplifier()).compile(module);
        assertEquals(49L, new IrInterpreter(defaultSimplifier()).eval(compiled));
    }

    // --- Function declaration + call ---

    @Test
    void unrefinedFunction_callableWithAnyArg() {
        // fn double(x: Int) -> Int = x + x
        IrStmt.FunctionDecl doubleDecl = IrStmt.functionDecl(
                "double",
                List.of(new IrParam("x", IrSort.named("Int"))),
                IrSort.named("Int"),
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.var("x")));

        IrModule module = new IrModule("doubler",
                List.of(doubleDecl),
                IrExpr.call("double", List.of(IrExpr.lit(21))));

        CompiledModule compiled = new IrCompiler(defaultSimplifier()).compile(module);
        assertEquals(42L, new IrInterpreter(defaultSimplifier()).eval(compiled));
    }

    // --- The headline: refinement-typed function ---

    @Test
    void headline_powWithRefinementTypes_callableWithSatisfyingArgs() {
        // fn pow(b: Int[@>0], x: Int[@>=1]) -> Int[@>0] = b
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        IrSort atLeastOne = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(1)));

        IrStmt.FunctionDecl powDecl = IrStmt.functionDecl(
                "pow",
                List.of(
                        new IrParam("b", positive),
                        new IrParam("x", atLeastOne)),
                positive,
                IrExpr.var("b"));

        IrModule module = new IrModule("powDemo",
                List.of(powDecl),
                IrExpr.call("pow", List.of(IrExpr.lit(5), IrExpr.lit(2))));

        CompiledModule compiled = new IrCompiler(defaultSimplifier()).compile(module);
        assertEquals(5L, new IrInterpreter(defaultSimplifier()).eval(compiled));
    }

    @Test
    void powCalledWithViolatingArg_throwsAtCall() {
        // pow(-3, 2): -3 violates @>0; should be caught by the dispatch/runtime check
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        IrSort atLeastOne = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(1)));

        IrStmt.FunctionDecl powDecl = IrStmt.functionDecl(
                "pow",
                List.of(
                        new IrParam("b", positive),
                        new IrParam("x", atLeastOne)),
                positive,
                IrExpr.var("b"));

        IrModule module = new IrModule("powBad",
                List.of(powDecl),
                IrExpr.call("pow", List.of(IrExpr.lit(-3), IrExpr.lit(2))));

        CompiledModule compiled = new IrCompiler(defaultSimplifier()).compile(module);
        assertThrows(RuntimeCheckException.class,
                () -> new IrInterpreter(defaultSimplifier()).eval(compiled));
    }

    @Test
    void powCalledWithViolatingSecondArg_throws() {
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        IrSort atLeastOne = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(1)));

        IrStmt.FunctionDecl powDecl = IrStmt.functionDecl(
                "pow",
                List.of(
                        new IrParam("b", positive),
                        new IrParam("x", atLeastOne)),
                positive,
                IrExpr.var("b"));

        IrModule module = new IrModule("powBad2",
                List.of(powDecl),
                IrExpr.call("pow", List.of(IrExpr.lit(5), IrExpr.lit(0))));

        CompiledModule compiled = new IrCompiler(defaultSimplifier()).compile(module);
        assertThrows(RuntimeCheckException.class,
                () -> new IrInterpreter(defaultSimplifier()).eval(compiled));
    }

    // --- Multi-dispatch via the IR ---

    @Test
    void twoOverloadsForSameName_specificOneWins() {
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        IrSort anyInt = IrSort.named("Int");

        // fn handle(x: Int[@>0]) -> Int = x * 100
        IrStmt.FunctionDecl tight = IrStmt.functionDecl(
                "handle",
                List.of(new IrParam("x", positive)),
                anyInt,
                IrExpr.binOp(IrExpr.Op.MUL, IrExpr.var("x"), IrExpr.lit(100)));

        // fn handle(x: Int) -> Int = x
        IrStmt.FunctionDecl loose = IrStmt.functionDecl(
                "handle",
                List.of(new IrParam("x", anyInt)),
                anyInt,
                IrExpr.var("x"));

        IrModule module = new IrModule("dispatch",
                List.of(tight, loose),
                IrExpr.call("handle", List.of(IrExpr.lit(5))));

        CompiledModule compiled = new IrCompiler(defaultSimplifier()).compile(module);
        // 5 satisfies @>0; tight overload wins; result = 5 * 100 = 500
        assertEquals(500L, new IrInterpreter(defaultSimplifier()).eval(compiled));
    }

    @Test
    void twoOverloadsForSameName_specificFails_falsthroughToGeneral() {
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        IrSort anyInt = IrSort.named("Int");

        IrStmt.FunctionDecl tight = IrStmt.functionDecl(
                "handle",
                List.of(new IrParam("x", positive)),
                anyInt,
                IrExpr.binOp(IrExpr.Op.MUL, IrExpr.var("x"), IrExpr.lit(100)));

        IrStmt.FunctionDecl loose = IrStmt.functionDecl(
                "handle",
                List.of(new IrParam("x", anyInt)),
                anyInt,
                IrExpr.var("x"));

        IrModule module = new IrModule("dispatch2",
                List.of(tight, loose),
                IrExpr.call("handle", List.of(IrExpr.lit(-7))));

        CompiledModule compiled = new IrCompiler(defaultSimplifier()).compile(module);
        // -7 fails tight's @>0; loose catches it; result = -7
        assertEquals(-7L, new IrInterpreter(defaultSimplifier()).eval(compiled));
    }

    // --- Composition: function calling function ---

    @Test
    void nestedFunctionCalls() {
        IrSort anyInt = IrSort.named("Int");

        // fn inc(n: Int) -> Int = n + 1
        IrStmt.FunctionDecl inc = IrStmt.functionDecl(
                "inc",
                List.of(new IrParam("n", anyInt)),
                anyInt,
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("n"), IrExpr.lit(1)));

        // fn double(n: Int) -> Int = n + n
        IrStmt.FunctionDecl dbl = IrStmt.functionDecl(
                "double",
                List.of(new IrParam("n", anyInt)),
                anyInt,
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("n"), IrExpr.var("n")));

        // main = double(inc(5)) = double(6) = 12
        IrModule module = new IrModule("compose",
                List.of(inc, dbl),
                IrExpr.call("double", List.of(IrExpr.call("inc", List.of(IrExpr.lit(5))))));

        CompiledModule compiled = new IrCompiler(defaultSimplifier()).compile(module);
        assertEquals(12L, new IrInterpreter(defaultSimplifier()).eval(compiled));
    }

    // --- LetIn + Call interaction ---

    @Test
    void letInBindsAndCallSeesBinding() {
        IrSort anyInt = IrSort.named("Int");

        IrStmt.FunctionDecl addOne = IrStmt.functionDecl(
                "addOne",
                List.of(new IrParam("n", anyInt)),
                anyInt,
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("n"), IrExpr.lit(1)));

        // let x = 10 in addOne(x)
        IrExpr main = IrExpr.letIn(
                "x", anyInt, IrExpr.lit(10),
                IrExpr.call("addOne", List.of(IrExpr.var("x"))));

        IrModule module = new IrModule("letCall", List.of(addOne), main);
        CompiledModule compiled = new IrCompiler(defaultSimplifier()).compile(module);
        assertEquals(11L, new IrInterpreter(defaultSimplifier()).eval(compiled));
    }
}
