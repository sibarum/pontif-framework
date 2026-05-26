package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.Simplifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase D.1: pairwise overload-overlap check at module compile time.
 * Three branches: provable overlap with no subsumption fails;
 * provable overlap WITH subsumption (the catch-all + specialization
 * pattern) passes; Unknown / disjoint cases pass.
 */
class OverloadOverlapTest {

    private static void compile(IrModule module) throws CompileException {
        new IrCompiler(new Simplifier(List.of())).compile(module);
    }

    // --- Disjoint cases ------------------------------------------------------

    @Test
    void differentArities_disjoint_compiles() {
        IrStmt.FunctionDecl arity1 = IrStmt.functionDecl(
                "f",
                List.of(new IrParam("x", IrSort.named("Int"))),
                IrSort.named("Int"), IrExpr.lit(0));
        IrStmt.FunctionDecl arity2 = IrStmt.functionDecl(
                "f",
                List.of(new IrParam("x", IrSort.named("Int")),
                        new IrParam("y", IrSort.named("Int"))),
                IrSort.named("Int"), IrExpr.lit(0));

        assertDoesNotThrow(() -> compile(new IrModule("m",
                List.of(arity1, arity2), IrExpr.lit(0))));
    }

    @Test
    void differentBaseSorts_disjoint_compiles() {
        IrStmt.FunctionDecl onInt = IrStmt.functionDecl(
                "f",
                List.of(new IrParam("x", IrSort.named("Int"))),
                IrSort.named("Int"), IrExpr.lit(0));
        IrStmt.FunctionDecl onBool = IrStmt.functionDecl(
                "f",
                List.of(new IrParam("x", IrSort.named("Bool"))),
                IrSort.named("Int"), IrExpr.lit(0));

        assertDoesNotThrow(() -> compile(new IrModule("m",
                List.of(onInt, onBool), IrExpr.lit(0))));
    }

    @Test
    void provablyDisjointRefinements_compiles() {
        // f(x:[Int:@>0]) and f(x:[Int:@<0]) — kernel proves disjoint.
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        IrSort negative = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.LT, IrExpr.self(), IrExpr.lit(0)));

        IrStmt.FunctionDecl pos = IrStmt.functionDecl(
                "f", List.of(new IrParam("x", positive)),
                IrSort.named("Int"), IrExpr.lit(0));
        IrStmt.FunctionDecl neg = IrStmt.functionDecl(
                "f", List.of(new IrParam("x", negative)),
                IrSort.named("Int"), IrExpr.lit(0));

        assertDoesNotThrow(() -> compile(new IrModule("m",
                List.of(pos, neg), IrExpr.lit(0))));
    }

    @Test
    void multiParam_disjointAtOnePosition_compiles() {
        // Two params, second position disjoint → overall disjoint.
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        IrSort negative = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.LT, IrExpr.self(), IrExpr.lit(0)));

        IrStmt.FunctionDecl a = IrStmt.functionDecl(
                "f",
                List.of(new IrParam("x", IrSort.named("Int")),
                        new IrParam("y", positive)),
                IrSort.named("Int"), IrExpr.lit(0));
        IrStmt.FunctionDecl b = IrStmt.functionDecl(
                "f",
                List.of(new IrParam("x", IrSort.named("Int")),
                        new IrParam("y", negative)),
                IrSort.named("Int"), IrExpr.lit(0));

        assertDoesNotThrow(() -> compile(new IrModule("m",
                List.of(a, b), IrExpr.lit(0))));
    }

    // --- Subsumption escape hatch -------------------------------------------

    @Test
    void catchAllPlusSpecialization_compiles() {
        // The headline pattern: f(x:Int) plus f(x:[Int:@>0]).
        // Provably overlap, but the refined is strictly more specific —
        // runtime resolves via most-specific. Should compile.
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));

        IrStmt.FunctionDecl catchAll = IrStmt.functionDecl(
                "f", List.of(new IrParam("x", IrSort.named("Int"))),
                IrSort.named("Int"), IrExpr.lit(0));
        IrStmt.FunctionDecl specialization = IrStmt.functionDecl(
                "f", List.of(new IrParam("x", positive)),
                IrSort.named("Int"), IrExpr.lit(0));

        assertDoesNotThrow(() -> compile(new IrModule("m",
                List.of(catchAll, specialization), IrExpr.lit(0))));
    }

    @Test
    void nestedRefinementSubsumption_compiles() {
        // f(x:[Int:@>=0]) and f(x:[Int:@>0]) — @>0 ⊂ @>=0; subsumed.
        IrSort nonNegative = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(0)));
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));

        IrStmt.FunctionDecl looser = IrStmt.functionDecl(
                "f", List.of(new IrParam("x", nonNegative)),
                IrSort.named("Int"), IrExpr.lit(0));
        IrStmt.FunctionDecl tighter = IrStmt.functionDecl(
                "f", List.of(new IrParam("x", positive)),
                IrSort.named("Int"), IrExpr.lit(0));

        assertDoesNotThrow(() -> compile(new IrModule("m",
                List.of(looser, tighter), IrExpr.lit(0))));
    }

    // --- Genuine overlap fails ----------------------------------------------

    @Test
    void irreducibleOverlap_fails() {
        // f(x:[Int:@>0]) and f(x:[Int:@<10]) — overlap on 1..9; neither
        // is more specific than the other. Should fail.
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
        IrSort lessThanTen = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.LT, IrExpr.self(), IrExpr.lit(10)));

        IrStmt.FunctionDecl a = IrStmt.functionDecl(
                "f", List.of(new IrParam("x", positive)),
                IrSort.named("Int"), IrExpr.lit(0));
        IrStmt.FunctionDecl b = IrStmt.functionDecl(
                "f", List.of(new IrParam("x", lessThanTen)),
                IrSort.named("Int"), IrExpr.lit(0));

        CompileException ex = assertThrows(CompileException.class,
                () -> compile(new IrModule("m", List.of(a, b), IrExpr.lit(0))));
        assertTrue(ex.getMessage().contains("Overloads of 'f' overlap"),
                () -> "Expected overlap message; got: " + ex.getMessage());
    }

    @Test
    void duplicateOverloads_fails() {
        // f(x:Int) declared twice — both identical → overlap at every
        // param, neither strictly more specific → fail.
        IrStmt.FunctionDecl first = IrStmt.functionDecl(
                "f", List.of(new IrParam("x", IrSort.named("Int"))),
                IrSort.named("Int"), IrExpr.lit(0));
        IrStmt.FunctionDecl second = IrStmt.functionDecl(
                "f", List.of(new IrParam("x", IrSort.named("Int"))),
                IrSort.named("Int"), IrExpr.lit(1));

        CompileException ex = assertThrows(CompileException.class,
                () -> compile(new IrModule("m", List.of(first, second), IrExpr.lit(0))));
        assertTrue(ex.getMessage().contains("Overloads of 'f' overlap"));
    }

    // --- Unknown cases (struct refinements) ---------------------------------

    @Test
    void structRefinedOverlap_kernelUnknown_compiles() {
        // f(p:[Point:@.x>0]) and f(p:[Point:@.x<0]) — kernel is Int-only
        // and can't decide struct refinements; Unknown → silent pass.
        Map<String, IrSort> pointMembers = new LinkedHashMap<>();
        pointMembers.put("x", IrSort.named("Int"));
        pointMembers.put("y", IrSort.named("Int"));
        IrStmt.TypeAlias pointAlias = IrStmt.typeAlias(
                "Point", IrSort.structural("Point", pointMembers));

        IrSort posX = IrSort.refined("Point",
                IrExpr.binOp(IrExpr.Op.GT,
                        IrExpr.fieldAccess(IrExpr.self(), "x"), IrExpr.lit(0)));
        IrSort negX = IrSort.refined("Point",
                IrExpr.binOp(IrExpr.Op.LT,
                        IrExpr.fieldAccess(IrExpr.self(), "x"), IrExpr.lit(0)));

        IrStmt.FunctionDecl a = IrStmt.functionDecl(
                "f", List.of(new IrParam("p", posX)),
                IrSort.named("Int"), IrExpr.lit(0));
        IrStmt.FunctionDecl b = IrStmt.functionDecl(
                "f", List.of(new IrParam("p", negX)),
                IrSort.named("Int"), IrExpr.lit(0));

        assertDoesNotThrow(() -> compile(new IrModule("m",
                List.of(pointAlias, a, b), IrExpr.lit(0))));
    }

    // --- Single overload doesn't trigger pairwise check ---------------------

    @Test
    void singleOverload_compiles() {
        IrStmt.FunctionDecl only = IrStmt.functionDecl(
                "f", List.of(new IrParam("x", IrSort.named("Int"))),
                IrSort.named("Int"), IrExpr.lit(0));

        assertDoesNotThrow(() -> compile(new IrModule("m",
                List.of(only), IrExpr.lit(0))));
    }
}
