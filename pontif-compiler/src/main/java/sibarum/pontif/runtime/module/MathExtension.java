package sibarum.pontif.runtime.module;

import sibarum.pontif.ir.NativeCalls;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

/**
 * The builtin <b>math extension</b> ({@code pontif.math}) — exactly the SPIR-V {@code GLSL.std.450}
 * extended-instruction math set, the functions that map 1:1 to a GPU opcode. Everything OUTSIDE
 * this set (integer number theory, combinatorics) lives in {@code pontif.math.ext}
 * ({@link MathExtExtension}). Installed by default (pure JDK, no external dep), like
 * {@link IoExtension}.
 *
 * <p><b>Precision (no-lie):</b> operations algebraically closed over {@code Decimal}
 * (abs/sign/floor/ceil/trunc/round/min/max/clamp/fract/mix/fma) are computed <em>exactly</em> with
 * {@link BigDecimal}. Transcendentals (trig/exp/log/sqrt/…) are computed at {@code double}
 * precision via {@link Math} and returned as {@link BigDecimal#valueOf(double)}, so the result's
 * scale reflects the ~16 honest significant digits of a double — never a spurious expansion
 * claiming exactness it doesn't have.
 */
public final class MathExtension implements Extension {

    public static final MathExtension INSTANCE = new MathExtension();

    private MathExtension() {}

    @Override
    public String moduleName() {
        return "pontif.math";
    }

    @Override
    public Map<String, NativeCalls.NativeCall> calls() {
        Map<String, NativeCalls.NativeCall> m = new LinkedHashMap<>();

        // --- Trig (GLSL Sin/Cos/Tan/Asin/Acos/Atan/Atan2), double ---
        m.put("sin", unary(Math::sin));
        m.put("cos", unary(Math::cos));
        m.put("tan", unary(Math::tan));
        m.put("asin", unary(Math::asin));
        m.put("acos", unary(Math::acos));
        m.put("atan", unary(Math::atan));
        m.put("atan2", binary(Math::atan2));   // atan2(y, x)

        // --- Hyperbolic (GLSL Sinh…Atanh), double. asinh/acosh/atanh not in java.lang.Math. ---
        m.put("sinh", unary(Math::sinh));
        m.put("cosh", unary(Math::cosh));
        m.put("tanh", unary(Math::tanh));
        m.put("asinh", unary(x -> Math.log(x + Math.sqrt(x * x + 1.0))));
        m.put("acosh", unary(x -> Math.log(x + Math.sqrt(x * x - 1.0))));
        m.put("atanh", unary(x -> 0.5 * Math.log((1.0 + x) / (1.0 - x))));

        // --- Exponential (GLSL Pow/Exp/Log/Exp2/Log2/Sqrt/InverseSqrt), double ---
        m.put("pow", binary(Math::pow));
        m.put("exp", unary(Math::exp));
        m.put("log", unary(Math::log));
        m.put("exp2", unary(x -> Math.pow(2.0, x)));
        m.put("log2", unary(x -> Math.log(x) / Math.log(2.0)));
        m.put("sqrt", unary(Math::sqrt));
        m.put("inverseSqrt", unary(x -> 1.0 / Math.sqrt(x)));

        // --- Common, EXACT over Decimal (GLSL FAbs/FSign/Floor/Ceil/Trunc/Round/FMin/FMax/…) ---
        m.put("abs", exact1(BigDecimal::abs));
        m.put("sign", exact1(x -> BigDecimal.valueOf(x.signum())));
        m.put("floor", exact1(x -> x.setScale(0, RoundingMode.FLOOR)));
        m.put("ceil", exact1(x -> x.setScale(0, RoundingMode.CEILING)));
        m.put("trunc", exact1(x -> x.setScale(0, RoundingMode.DOWN)));
        m.put("round", exact1(x -> x.setScale(0, RoundingMode.HALF_UP)));
        m.put("fract", exact1(x -> x.subtract(x.setScale(0, RoundingMode.FLOOR))));
        m.put("min", exact2(BigDecimal::min));
        m.put("max", exact2(BigDecimal::max));
        m.put("clamp", (args, ctx) -> d(args.get(0)).max(d(args.get(1))).min(d(args.get(2))));
        m.put("mix", (args, ctx) -> {          // a + (b - a) * t
            BigDecimal a = d(args.get(0)), b = d(args.get(1)), t = d(args.get(2));
            return a.add(b.subtract(a).multiply(t));
        });
        m.put("fma", (args, ctx) ->            // a * b + c
                d(args.get(0)).multiply(d(args.get(1))).add(d(args.get(2))));

        // --- Common, double (division/constants) ---
        m.put("step", binary((edge, x) -> x < edge ? 0.0 : 1.0));   // step(edge, x)
        m.put("smoothstep", (args, ctx) -> {                        // smoothstep(e0, e1, x)
            double e0 = d(args.get(0)).doubleValue(), e1 = d(args.get(1)).doubleValue();
            double x = d(args.get(2)).doubleValue();
            double t = Math.max(0.0, Math.min(1.0, (x - e0) / (e1 - e0)));
            return BigDecimal.valueOf(t * t * (3.0 - 2.0 * t));
        });
        m.put("radians", unary(Math::toRadians));
        m.put("degrees", unary(Math::toDegrees));

        return m;
    }

    // --- native-call builders ---

    private static NativeCalls.NativeCall unary(DoubleUnaryOperator f) {
        return (args, ctx) -> BigDecimal.valueOf(f.applyAsDouble(d(args.get(0)).doubleValue()));
    }

    private static NativeCalls.NativeCall binary(DoubleBinaryOperator f) {
        return (args, ctx) -> BigDecimal.valueOf(
                f.applyAsDouble(d(args.get(0)).doubleValue(), d(args.get(1)).doubleValue()));
    }

    private static NativeCalls.NativeCall exact1(java.util.function.UnaryOperator<BigDecimal> f) {
        return (args, ctx) -> f.apply(d(args.get(0)));
    }

    private static NativeCalls.NativeCall exact2(java.util.function.BinaryOperator<BigDecimal> f) {
        return (args, ctx) -> f.apply(d(args.get(0)), d(args.get(1)));
    }

    /** Coerce a Decimal (BigDecimal) / promoted Int (Long/Integer) argument to BigDecimal. */
    private static BigDecimal d(Object o) {
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Long l) return BigDecimal.valueOf(l);
        if (o instanceof Integer i) return BigDecimal.valueOf(i);
        return BigDecimal.ZERO;
    }
}
