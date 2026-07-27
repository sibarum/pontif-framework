package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edge-case tests for the compile-time type-system passes ({@link SortChecker}, {@link
 * NarrowingInference}, and the rewrite gates) surfaced by the type-system audit.
 *
 * <p>Regression tests assert a fix; characterization tests (marked <b>BUG</b>) pin the CURRENT
 * behavior of a known-but-unfixed defect so the suite stays green and the defect cannot drift
 * silently — the moment the defect is fixed the test fails and must be flipped to the desired
 * assertion noted inline. See the audit summary for the full rationale on each.
 */
class TypeSystemEdgeCaseTest {

    // === Regression: DestructureResolver tolerates a null module.main() (finding 8) ==========

    @Test
    void destructureResolver_nullMain_isCarriedThrough() {
        IrModule m = new IrModule("m", List.of(), null);
        IrModule[] out = new IrModule[1];
        assertDoesNotThrow(() -> out[0] = DestructureResolver.rewrite(m));
        assertNull(out[0].main(), "a null main must survive the pass, not NPE");
    }

    // === Characterization (BUG): unresolved MethodCall crashes SortChecker (finding 5) =======

    @Test
    void methodCallInBody_throwsUncheckedNotCompileException() {
        // A MethodCall reaching SortChecker (MethodResolver did not run first) blows up with an
        // unchecked IllegalStateException instead of a CompileException.
        // BUG (finding 5). DESIRED: a CompileException, OR document this as an internal phase-order
        // invariant. When resolved, replace with the chosen exception type.
        IrExpr body = IrExpr.fieldAccess(
                new IrExpr.MethodCall(IrExpr.var("x"), "foo", List.of(), Origin.NONE), "z");
        IrStmt.FunctionDecl fd = IrStmt.functionDecl(
                "f", List.of(new IrParam("x", IrSort.named("Int"))), IrSort.named("Int"), body);
        IrModule m = new IrModule("m", List.of(fd), IrExpr.lit(0));
        assertThrows(IllegalStateException.class, () -> SortChecker.check(m));
    }

    // === Regression: parametric type-arg arity is now checked in sort position (fix finding 2) ====

    @Test
    void wrongParametricArityInSortPosition_isRejected() {
        // struct Box[type T](value:T), then a param typed Box[Int, Bool] (two args for one type param).
        // Now rejected with a CompileException naming the arity mismatch. (Was silently accepted.)
        IrSort.Structural box = new IrSort.Structural("Box",
                Map.of("value", IrSort.named("T")), null,
                new LinkedHashMap<>(Map.of("T", IrSort.named("Int"))), Origin.NONE);
        IrStmt boxAlias = IrStmt.typeAlias("Box", box);
        IrSort badRef = new IrSort.Named("Box",
                List.of(IrSort.named("Int"), IrSort.named("Bool")), Origin.NONE);
        IrStmt.FunctionDecl g = IrStmt.functionDecl(
                "g", List.of(new IrParam("b", badRef)), IrSort.named("Int"), IrExpr.lit(0));
        IrModule m = new IrModule("m", List.of(boxAlias, g), IrExpr.lit(0));
        CompileException ex = assertThrows(CompileException.class, () -> SortChecker.check(m));
        assertTrue(ex.getMessage().contains("Box") && ex.getMessage().contains("type"),
                () -> "unexpected: " + ex.getMessage());
    }

    @Test
    void correctParametricArityInSortPosition_isAccepted() {
        // The fix must not over-reject: Box[Int] (one arg, one param) still validates.
        IrSort.Structural box = new IrSort.Structural("Box",
                Map.of("value", IrSort.named("T")), null,
                new LinkedHashMap<>(Map.of("T", IrSort.named("Int"))), Origin.NONE);
        IrStmt boxAlias = IrStmt.typeAlias("Box", box);
        IrSort goodRef = new IrSort.Named("Box", List.of(IrSort.named("Int")), Origin.NONE);
        IrStmt.FunctionDecl g = IrStmt.functionDecl(
                "g", List.of(new IrParam("b", goodRef)), IrSort.named("Int"), IrExpr.lit(0));
        IrModule m = new IrModule("m", List.of(boxAlias, g), IrExpr.lit(0));
        assertDoesNotThrow(() -> SortChecker.check(m));
    }

    // === Characterization (BUG): validateDecimalNarrow admits @ != const (finding 7) =========

    @Test
    void decimalNotEqualRefinement_isAccepted() {
        // The Decimal-narrow vocabulary is documented as sign / range / equality only, yet NE is on the
        // whitelist, so [Decimal:@ != 5] validates clean.
        // BUG (finding 7). DESIRED: a "Not a Decimal narrow" CompileException (or update the doc if
        // != is intended as anti-equality).
        IrSort.Refined refined = new IrSort.Refined("Decimal", List.of(),
                IrExpr.binOp(IrExpr.Op.NE, IrExpr.self(), IrExpr.lit(5)), Origin.NONE);
        IrStmt.FunctionDecl h = IrStmt.functionDecl(
                "h", List.of(new IrParam("d", refined)), IrSort.named("Int"), IrExpr.lit(0));
        IrModule m = new IrModule("m", List.of(h), IrExpr.lit(0));
        assertDoesNotThrow(() -> SortChecker.check(m), "characterizes the current over-permissive accept");
    }

    // === Guard: an impossible refinement cast is a compile error via CastGate (finding 4) =========

    @Test
    void castToRefinementWithNoPath_isFlaggedByCastGate() {
        // NarrowingInference.infer, in isolation, DOES narrow a cast straight to its declared target
        // (so `cast -5 to [Int:@>0]` infers [Int:@>0]). That local over-narrowing is harmless because
        // the value never escapes as truth: CastGate rejects any cast to a refined target that has no
        // runtime path (no String render, no matching user `cast` coercion), so PontifCompiler fails
        // the compile before the narrowing is trusted. This test pins that backstop — the reason
        // finding 4 is NOT a live soundness hole. (If a refinement-narrowing cast ever gains a real
        // runtime path, this expectation changes and infer's honesty becomes load-bearing.)
        IrExpr cast = new IrExpr.Cast(
                IrSort.refined("Int", IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0))),
                IrExpr.lit(-5), Origin.NONE);
        IrStmt.FunctionDecl f = IrStmt.functionDecl("f", List.of(), IrSort.named("Int"), cast);
        IrModule m = new IrModule("m", List.of(f), IrExpr.lit(0));
        assertTrue(CastGate.firstIllegal(m).isPresent(),
                "CastGate must reject a refined-target cast with no runtime path");

        // And the local infer behavior it backstops, documented for completeness.
        IrSort inferred = NarrowingInference.infer(cast, InferenceContext.empty());
        assertEquals("Int", ((IrSort.Refined) inferred).name());
    }
}
