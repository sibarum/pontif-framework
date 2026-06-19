package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.DispatchTable;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.defaults.DefaultRules;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Honesty tests for the interpreter's Char/String operator backstops.
 *
 * <p>After the dispatch war's Step A, {@code MethodOperatorResolver.checkOperatorComplete}
 * rejects every operator the runtime would refuse — and it runs on every path
 * that reaches the interpreter ({@code IrCompiler.compile} invokes it). So the
 * "not defined for Char/String" throws in {@link IrInterpreter} are unreachable
 * through a checked compile; {@code CharAltTest}/{@code StringAltTest} prove the
 * undefined cases are now <em>compile</em> errors.
 *
 * <p>The throws nonetheless remain as defense-in-depth for hand-built IR (the
 * operator analog of the match no-match safety net that {@code IrMatchTest} keeps
 * honest via {@code compileUnchecked}). These tests bypass {@link IrCompiler} —
 * and so the compile-time gate — to confirm the backstop still fails closed,
 * keeping it honest rather than letting it rot into dead code.
 */
class IrOperatorBackstopTest {

    private static Object runUnchecked(IrExpr main) throws Exception {
        Simplifier simp = new Simplifier(DefaultRules.production());
        // Hand-built CompiledModule, skipping IrCompiler (and so the
        // checkOperatorComplete gate) — the only way to reach the backstop.
        CompiledModule compiled = new CompiledModule(
                "t", new DispatchTable(), Map.of(), main,
                new java.util.IdentityHashMap<>(), Map.of(), List.of());
        return new IrInterpreter(simp).eval(compiled);
    }

    @Test
    void charArithmetic_backstopFailsClosed() {
        RuntimeCheckException e = assertThrows(RuntimeCheckException.class,
                () -> runUnchecked(IrExpr.binOp(IrExpr.Op.ADD, IrExpr.chr('a'), IrExpr.chr('b'))));
        assertTrue(e.getMessage().contains("don't compute"), () -> e.getMessage());
    }

    @Test
    void mixedCharComparison_backstopFailsClosed() {
        RuntimeCheckException e = assertThrows(RuntimeCheckException.class,
                () -> runUnchecked(IrExpr.binOp(IrExpr.Op.LT, IrExpr.chr('a'), IrExpr.lit(97))));
        assertTrue(e.getMessage().contains("Char compares only with Char"), () -> e.getMessage());
    }

    @Test
    void stringArithmetic_backstopFailsClosed() {
        RuntimeCheckException e = assertThrows(RuntimeCheckException.class,
                () -> runUnchecked(IrExpr.binOp(IrExpr.Op.SUB, IrExpr.str("a"), IrExpr.str("b"))));
        assertTrue(e.getMessage().contains("only '+' concatenates"), () -> e.getMessage());
    }

    @Test
    void mixedStringComparison_backstopFailsClosed() {
        RuntimeCheckException e = assertThrows(RuntimeCheckException.class,
                () -> runUnchecked(IrExpr.binOp(IrExpr.Op.LT, IrExpr.str("a"), IrExpr.lit(1))));
        assertTrue(e.getMessage().contains("String compares only with String"), () -> e.getMessage());
    }
}
