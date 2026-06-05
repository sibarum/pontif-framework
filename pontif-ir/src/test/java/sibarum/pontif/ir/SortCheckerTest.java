package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SortChecker}'s compile-time validation:
 * unknown sort names, unknown function calls, and the existing
 * field-access-against-structural-sort check.
 */
class SortCheckerTest {

    /** Helper: validate via the full IrCompiler pipeline (AliasResolver + SortChecker). */
    private static void compile(IrModule module) throws CompileException {
        new IrCompiler(new sibarum.pontif.core.symbolic.Simplifier(List.of()))
                .compile(module);
    }

    // --- Unknown sort names --------------------------------------------------

    @Test
    void unknownParamSort_throws() {
        Origin sortOrigin = Origin.at("t.ptf", 3, 17);
        IrSort gibberish = new IrSort.Named("Zzzzz", sortOrigin);
        IrStmt.FunctionDecl fd = new IrStmt.FunctionDecl(
                "f",
                List.of(new IrParam("p", gibberish)),
                IrSort.named("Int"),
                IrExpr.lit(0),
                Origin.NONE);
        IrModule m = new IrModule("m", List.of(fd), IrExpr.lit(0));

        CompileException ex = assertThrows(CompileException.class, () -> compile(m));
        assertTrue(ex.getMessage().contains("Zzzzz"),
                () -> "Unexpected: " + ex.getMessage());
        assertEquals(sortOrigin, ex.origin());
    }

    @Test
    void unknownReturnSort_throws() {
        Origin sortOrigin = Origin.at("t.ptf", 5, 12);
        IrSort gibberish = new IrSort.Named("Xxxxx", sortOrigin);
        IrStmt.FunctionDecl fd = new IrStmt.FunctionDecl(
                "f", List.of(), gibberish, IrExpr.lit(0), Origin.NONE);
        IrModule m = new IrModule("m", List.of(fd), IrExpr.lit(0));

        CompileException ex = assertThrows(CompileException.class, () -> compile(m));
        assertTrue(ex.getMessage().contains("Xxxxx"));
        assertEquals(sortOrigin, ex.origin());
    }

    @Test
    void unknownRefinedBase_throws() {
        Origin sortOrigin = Origin.at("t.ptf", 1, 1);
        IrSort refined = new IrSort.Refined(
                "Yyyy",
                IrExpr.bool(true),
                sortOrigin);
        IrStmt.FunctionDecl fd = new IrStmt.FunctionDecl(
                "f",
                List.of(new IrParam("p", refined)),
                IrSort.named("Int"),
                IrExpr.lit(0),
                Origin.NONE);
        IrModule m = new IrModule("m", List.of(fd), IrExpr.lit(0));

        CompileException ex = assertThrows(CompileException.class, () -> compile(m));
        assertTrue(ex.getMessage().toLowerCase().contains("yyyy"));
        assertEquals(sortOrigin, ex.origin());
    }

    @Test
    void unknownSortInsideStructuralMembers_throws() {
        Origin sortOrigin = Origin.at("t.ptf", 2, 8);
        IrSort.Structural structural = new IrSort.Structural(
                "P",
                java.util.Map.of("x", new IrSort.Named("Bogus", sortOrigin)),
                Origin.NONE);
        IrStmt.FunctionDecl fd = new IrStmt.FunctionDecl(
                "f",
                List.of(new IrParam("p", structural)),
                IrSort.named("Int"),
                IrExpr.lit(0),
                Origin.NONE);
        IrModule m = new IrModule("m", List.of(fd), IrExpr.lit(0));

        CompileException ex = assertThrows(CompileException.class, () -> compile(m));
        assertTrue(ex.getMessage().contains("Bogus"));
    }

    @Test
    void primitiveSorts_accepted() throws Exception {
        // Int and Bool are primitives — should pass.
        IrStmt.FunctionDecl fd = new IrStmt.FunctionDecl(
                "f",
                List.of(new IrParam("p", IrSort.named("Int"))),
                IrSort.named("Bool"),
                IrExpr.bool(true),
                Origin.NONE);
        IrModule m = new IrModule("m", List.of(fd), IrExpr.lit(0));
        compile(m);  // must not throw
    }

    @Test
    void declaredAlias_accepted() throws Exception {
        // struct Point(x:Int, y:Int) — alias gets resolved before SortChecker.
        IrSort.Structural pointSort = new IrSort.Structural(
                "Point",
                java.util.Map.of("x", IrSort.named("Int"), "y", IrSort.named("Int")),
                Origin.NONE);
        IrStmt typeAlias = new IrStmt.TypeAlias("Point", pointSort, Origin.NONE);
        IrStmt.FunctionDecl fd = new IrStmt.FunctionDecl(
                "f",
                List.of(new IrParam("p", IrSort.named("Point"))),
                IrSort.named("Int"),
                new IrExpr.FieldAccess(IrExpr.var("p"), "x", Origin.NONE),
                Origin.NONE);
        IrModule m = new IrModule("m", List.of(typeAlias, fd), IrExpr.lit(0));
        compile(m);  // must not throw
    }

    // --- Unknown function calls ---------------------------------------------

    @Test
    void unknownFunctionCall_throws() {
        Origin callOrigin = Origin.at("t.ptf", 4, 5);
        IrExpr badCall = new IrExpr.Call("nonExistent", List.of(IrExpr.lit(1)), callOrigin);
        IrModule m = new IrModule("m", List.of(), badCall);

        CompileException ex = assertThrows(CompileException.class, () -> compile(m));
        assertTrue(ex.getMessage().contains("nonExistent"));
        assertEquals(callOrigin, ex.origin());
    }

    @Test
    void callToDeclaredFunction_accepted() throws Exception {
        IrStmt.FunctionDecl fd = new IrStmt.FunctionDecl(
                "double",
                List.of(new IrParam("n", IrSort.named("Int"))),
                IrSort.named("Int"),
                IrExpr.binOp(IrExpr.Op.MUL, IrExpr.var("n"), IrExpr.lit(2)),
                Origin.NONE);
        IrExpr main = new IrExpr.Call("double", List.of(IrExpr.lit(5)), Origin.NONE);
        IrModule m = new IrModule("m", List.of(fd), main);
        compile(m);
    }

    @Test
    void callToLetBoundName_accepted() throws Exception {
        // let f = (\x -> x + 1) in f(5)
        // f is locally bound — must be allowed as a Call target.
        IrExpr lambda = IrExpr.lambda(
                List.of(new IrParam("x", IrSort.named("Int"))),
                IrSort.named("Int"),
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.lit(1)));
        IrExpr body = new IrExpr.Call("f", List.of(IrExpr.lit(5)), Origin.NONE);
        IrSort fnSort = new IrSort.Method(
                List.of(IrSort.named("Int")), IrSort.named("Int"), Origin.NONE);
        IrExpr letIn = IrExpr.letIn("f", fnSort, lambda, body);
        IrModule m = new IrModule("m", List.of(), letIn);
        compile(m);
    }

    @Test
    void callToFunctionParam_accepted() throws Exception {
        // function apply(f:Function, x:Int):Int -> f(x)
        // f is a param; calling it must be allowed.
        IrSort fnSort = new IrSort.Method(
                List.of(IrSort.named("Int")), IrSort.named("Int"), Origin.NONE);
        IrStmt.FunctionDecl fd = new IrStmt.FunctionDecl(
                "apply",
                List.of(
                        new IrParam("f", fnSort),
                        new IrParam("x", IrSort.named("Int"))),
                IrSort.named("Int"),
                new IrExpr.Call("f", List.of(IrExpr.var("x")), Origin.NONE),
                Origin.NONE);
        IrModule m = new IrModule("m", List.of(fd), IrExpr.lit(0));
        compile(m);
    }

    // --- The user's gibberish example ---------------------------------------

    @Test
    void userGibberishExample_failsAtCompileWithClearMessage() {
        // function shifted(p:Zzzzz):Xxxxx -> {
        //   let dx = p.x + 1
        //   let dy = p.y + 1
        //   Qqqqq(dx, dy)
        // }
        //
        // Three problems: Zzzzz, Xxxxx (unknown sorts), and Qqqqq (unknown
        // function). SortChecker should reject the FIRST one it encounters
        // (param sort), with a clear message pointing at the offending name.
        Origin paramSort = Origin.at("t.ptf", 1, 19);
        IrStmt.FunctionDecl fd = new IrStmt.FunctionDecl(
                "shifted",
                List.of(new IrParam("p", new IrSort.Named("Zzzzz", paramSort))),
                new IrSort.Named("Xxxxx", Origin.NONE),
                new IrExpr.Call("Qqqqq", List.of(IrExpr.lit(1), IrExpr.lit(2)), Origin.NONE),
                Origin.NONE);
        IrModule m = new IrModule("m", List.of(fd), IrExpr.lit(0));

        CompileException ex = assertThrows(CompileException.class, () -> compile(m));
        // Either Zzzzz (params validated first) or Xxxxx is fine — both are
        // the right kind of error. Just verify it's a sort error.
        assertTrue(ex.getMessage().contains("Zzzzz") || ex.getMessage().contains("Xxxxx"),
                () -> "Expected unknown-sort error; got: " + ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase().contains("unknown sort")
                        || ex.getMessage().toLowerCase().contains("not a primitive"),
                () -> "Expected user-friendly message; got: " + ex.getMessage());
    }
}
