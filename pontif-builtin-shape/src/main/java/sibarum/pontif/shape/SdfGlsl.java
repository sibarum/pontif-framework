package sibarum.pontif.shape;

import sibarum.pontif.ast.record.RecordValue;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.ir.NativeCalls;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lowers a {@code pontif.shape} {@code SdfShape} value to a GLSL {@code float map(vec3 p)}
 * for Dasum's {@code RaymarchLayer} (docs/sdf-glsl.md). The shape's signed-distance function
 * IS a GLSL {@code map} once emitted, so a shape renders as a real GPU sphere-traced surface
 * instead of the sampled 24³ glow preview.
 *
 * <p><b>Slice 0 — the wiring spike.</b> Sphere only, near-hardcoded, purely to prove the pipe
 * (shape value → GLSL string → {@code RaymarchLayer} → scene). {@link #emit} is written to
 * recurse over a point-expression so slice 1 can drop in the general body-inlining lowerer
 * (which reads each shape's actual {@code distance} method IR, handles composites/transforms
 * and user {@code assign trait X:SdfShape}, and fails closed on the non-GLSL subset) without
 * reshaping the boundary. The GLSL is generated here, interpreter-side, and crosses to
 * {@code DasumBridge} as an inert string — no IR crosses the native boundary.
 */
public final class SdfGlsl {

    private SdfGlsl() {}

    /** Native {@code sdfMap(s:[SdfShape]):String} — the full {@code float map(vec3 p){…}}. */
    public static Object map(List<Object> args, NativeCalls.Context ctx) {
        if (args.isEmpty() || !(args.get(0) instanceof RecordValue shape)) {
            throw new IllegalStateException("sdfMap expects a shape value");
        }
        return new StringValue("float map(vec3 p){ return " + emit(shape, "p") + "; }");
    }

    /** The signed-distance GLSL expression for {@code shape} evaluated at point-expr {@code p}. */
    private static String emit(RecordValue shape, String p) {
        String type = bareType(shape.typeName());
        return switch (type) {
            case "Sphere" -> "length(" + p + ") - " + glslFloat(num(shape, "radius"));
            default -> throw new IllegalStateException(
                    "sdfMap: only Sphere is supported in the wiring spike (slice 0); got '" + type
                            + "'. The general body-inlining lowerer (slice 1) handles composites,"
                            + " transforms, and user shapes.");
        };
    }

    private static double num(RecordValue rv, String field) {
        Object v = rv.members().get(field);
        if (v instanceof BigDecimal d) return d.doubleValue();
        if (v instanceof Long l) return l;
        return 0.0;
    }

    /** Formats a constant as a GLSL float literal (always carrying a decimal point). */
    private static String glslFloat(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new IllegalStateException("sdfMap: non-finite constant " + v);
        }
        return Double.toString(v);   // e.g. "1.0", "0.8" — a valid GLSL float literal
    }

    private static String bareType(String typeName) {
        if (typeName == null) return "";
        int slash = typeName.lastIndexOf('/');
        return slash < 0 ? typeName : typeName.substring(slash + 1);
    }
}
