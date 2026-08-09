package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link EffectGate} — the effect gate for the {@code let}-led preamble (cut 1b). A {@code let EXPR}
 * discard lowers to a {@code #discard#}-named {@link IrExpr.LetIn}; the gate rejects one whose value is
 * <b>provably</b> effect-free, and — by the ratified fail-open direction — leaves every doubtful case alone
 * (an {@code emit}, a call to an emitting function, a call to an unclassified native).
 */
class EffectGateTest {

    private static IrExpr discard(IrExpr value, IrExpr cont) {
        return new IrExpr.LetIn("#discard#0", IrSort.named("_"), value, cont, Origin.NONE);
    }

    private static IrModule mainOnly(IrExpr main, IrStmt... stmts) {
        return new IrModule("t", List.of(stmts), main);
    }

    private static IrStmt.FunctionDecl fn(String name, IrExpr body) {
        return new IrStmt.FunctionDecl(name, List.of(), IrSort.named("_"), body, Origin.NONE);
    }

    // --- forbidden: a provably effect-free discard --------------------------

    @Test
    void discard_ofPureArithmetic_isRejected() {
        // `let 1 + 2  0` — the discard has no possible effect.
        IrExpr main = discard(new IrExpr.BinOp(IrExpr.Op.ADD, IrExpr.lit(1), IrExpr.lit(2), Origin.NONE),
                IrExpr.lit(0));
        CompileException ex = assertThrows(CompileException.class, () -> EffectGate.check(mainOnly(main)));
        assertTrue(ex.getMessage().contains("let _ ="), "error should point at the escape hatch");
    }

    @Test
    void discard_ofBareValue_isRejected() {
        // `let 5  0`
        CompileException ex = assertThrows(CompileException.class,
                () -> EffectGate.check(mainOnly(discard(IrExpr.lit(5), IrExpr.lit(0)))));
        assertTrue(ex.getMessage().contains("has no effect"));
    }

    @Test
    void discard_ofProvablyPureUserFunction_isRejected() {
        // `function answer():_ -> 42` then `let answer()  0` — answer is proven pure by the fixpoint.
        IrExpr main = discard(IrExpr.call("answer", List.of()), IrExpr.lit(0));
        assertThrows(CompileException.class,
                () -> EffectGate.check(mainOnly(main, fn("answer", IrExpr.lit(42)))));
    }

    // --- allowed: anything not provably pure (fail open) --------------------

    @Test
    void discard_ofEmittingFunction_isAllowed() {
        // `function ping():_ -> emit StdOut("hi")  0` then `let ping()  0` — ping emits, so it is not pure.
        IrExpr pingBody = new IrExpr.Emit(IrExpr.call("StdOut", List.of(IrExpr.str("hi"))), IrExpr.lit(0),
                Origin.NONE);
        IrExpr main = discard(IrExpr.call("ping", List.of()), IrExpr.lit(0));
        assertDoesNotThrow(() -> EffectGate.check(mainOnly(main, fn("ping", pingBody))));
    }

    @Test
    void discard_ofUnclassifiedNative_isAllowed() {
        // `let show(3)  0` — `show` has no declaration here, so it cannot be proven pure; fail open.
        IrExpr main = discard(IrExpr.call("show", List.of(IrExpr.lit(3))), IrExpr.lit(0));
        assertDoesNotThrow(() -> EffectGate.check(mainOnly(main)));
    }

    @Test
    void discard_ofDirectEmit_isAllowed() {
        // `let (emit Ev()  0)  0` — the discarded expression is itself an emit.
        IrExpr emit = new IrExpr.Emit(IrExpr.call("Ev", List.of()), IrExpr.lit(0), Origin.NONE);
        assertDoesNotThrow(() -> EffectGate.check(mainOnly(discard(emit, IrExpr.lit(0)))));
    }

    // --- the escape hatch: `let _ = EXPR` is a binding, not a discard -------

    @Test
    void explicitUnderscoreBinding_ofPureExpr_isAllowed() {
        IrExpr main = new IrExpr.LetIn("_", IrSort.named("_"),
                new IrExpr.BinOp(IrExpr.Op.ADD, IrExpr.lit(1), IrExpr.lit(2), Origin.NONE),
                IrExpr.lit(0), Origin.NONE);
        assertDoesNotThrow(() -> EffectGate.check(mainOnly(main)));
    }
}
