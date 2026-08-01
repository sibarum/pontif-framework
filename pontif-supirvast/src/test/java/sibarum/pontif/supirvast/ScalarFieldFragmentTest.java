package sibarum.pontif.supirvast;

import dev.supirvast.vastir.tools.NativeTools;
import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.IrExpr;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The first Pontif → {@code core} → SPIR-V <em>fragment</em>: a 2D scalar field authored as ordinary Pontif
 * arithmetic in {@code (x, y)} lowers to a valid fragment shader. This is the IR path the whole graphics
 * effort is built on — no GLSL text, no hand-authored core AST for the body; {@link ScalarFieldFragment}
 * reuses {@link ExprLowering} exactly as the compute kernels do.
 */
class ScalarFieldFragmentTest {

    private static final Origin O = Origin.NONE;

    /** {@code (x - 0.5)*(x - 0.5) + (y - 0.5)*(y - 0.5)} — squared distance from the screen center. */
    private static IrExpr centeredSquaredDistance() {
        IrExpr x = new IrExpr.Var("x", O);
        IrExpr y = new IrExpr.Var("y", O);
        IrExpr half = new IrExpr.Dec(new BigDecimal("0.5"), O);
        IrExpr dx = new IrExpr.BinOp(IrExpr.Op.SUB, x, half, O);
        IrExpr dy = new IrExpr.BinOp(IrExpr.Op.SUB, y, half, O);
        IrExpr dx2 = new IrExpr.BinOp(IrExpr.Op.MUL, dx, dx, O);
        IrExpr dy2 = new IrExpr.BinOp(IrExpr.Op.MUL, dy, dy, O);
        return new IrExpr.BinOp(IrExpr.Op.ADD, dx2, dy2, O);
    }

    /**
     * {@code length(Vec2(x - 0.5, y - 0.5)) - 0.3} — a real 2D circle SDF: a vector record, a vector subtract,
     * the {@code length} intrinsic, and a scalar subtract. Exercises the G1 shader vocabulary end to end.
     */
    private static IrExpr circleSdf() {
        IrExpr x = new IrExpr.Var("x", O);
        IrExpr y = new IrExpr.Var("y", O);
        IrExpr half = new IrExpr.Dec(new BigDecimal("0.5"), O);
        Map<String, IrExpr> components = new LinkedHashMap<>();
        components.put("x", new IrExpr.BinOp(IrExpr.Op.SUB, x, half, O));
        components.put("y", new IrExpr.BinOp(IrExpr.Op.SUB, y, half, O));
        IrExpr p = new IrExpr.Record("Vec2", components, null, O);
        IrExpr length = new IrExpr.Call("length", List.of(p), O);
        return new IrExpr.BinOp(IrExpr.Op.SUB, length, new IrExpr.Dec(new BigDecimal("0.3"), O), O);
    }

    @Test
    void circleSdfLowersToValidFragment() {
        NativeTools tools = new NativeTools();
        assumeTrue(tools.isAvailable(), "native SPIR-V toolchain not bundled for this platform");

        byte[] spirv = ScalarFieldFragment.lower(List.of("x", "y"), circleSdf());

        NativeTools.ValidationResult validation = tools.validate(spirv);
        assertTrue(validation.valid(),
                () -> "spirv-val rejected the Pontif circle-SDF fragment:\n" + validation.output());
    }

    @Test
    void scalarFieldLowersToValidFragment() {
        NativeTools tools = new NativeTools();
        assumeTrue(tools.isAvailable(), "native SPIR-V toolchain not bundled for this platform");

        byte[] spirv = ScalarFieldFragment.lower(List.of("x", "y"), centeredSquaredDistance());

        NativeTools.ValidationResult validation = tools.validate(spirv);
        assertTrue(validation.valid(),
                () -> "spirv-val rejected the Pontif-derived fragment:\n" + validation.output());

        String glsl = tools.crossCompile(spirv, NativeTools.ShaderLanguage.GLSL);
        assertFalse(glsl.isBlank(), "the Pontif-derived fragment produced no GLSL");
    }
}
