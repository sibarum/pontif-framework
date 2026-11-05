package sibarum.pontif.runtime.module;

import sibarum.pontif.ir.NativeCalls;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The builtin <b>math-extras extension</b> ({@code pontif.math.ext}) — math utilities that are
 * NOT in the SPIR-V {@code GLSL.std.450} set (integer number theory, combinatorics), so they have
 * no GPU opcode and stay CPU-only. Kept out of {@link MathExtension} ({@code pontif.math}) so the
 * GPU-lowerable surface is a hard, importable boundary. Installed by default (pure JDK), like
 * {@link IoExtension}.
 *
 * <p>Int arguments arrive as {@code Long}; results return as {@code Long} (a Pontif {@code Int}).
 * Exact via {@link BigInteger}; a result that overflows 64-bit {@code Int} throws loudly
 * ({@code longValueExact}) rather than silently truncating.
 */
public final class MathExtExtension implements Extension {

    public static final MathExtExtension INSTANCE = new MathExtExtension();

    private MathExtExtension() {}

    @Override
    public String moduleName() {
        return "pontif.math.ext";
    }

    @Override
    public Map<String, NativeCalls.NativeCall> calls() {
        Map<String, NativeCalls.NativeCall> m = new LinkedHashMap<>();
        m.put("gcd", (args, ctx) -> big(args, 0).gcd(big(args, 1)).longValueExact());
        m.put("lcm", (args, ctx) -> {
            BigInteger a = big(args, 0), b = big(args, 1);
            if (a.signum() == 0 || b.signum() == 0) return 0L;
            return a.divide(a.gcd(b)).multiply(b).abs().longValueExact();
        });
        m.put("factorial", (args, ctx) -> factorial(l(args, 0)).longValueExact());
        m.put("isqrt", (args, ctx) -> big(args, 0).sqrt().longValueExact());   // requires n >= 0
        m.put("modpow", (args, ctx) ->
                big(args, 0).modPow(big(args, 1), big(args, 2)).longValueExact());
        m.put("choose", (args, ctx) -> choose(l(args, 0), l(args, 1)).longValueExact());
        m.put("perm", (args, ctx) -> {          // n! / (n-k)!
            long n = l(args, 0), k = l(args, 1);
            return factorial(n).divide(factorial(n - k)).longValueExact();
        });
        m.put("floorDiv", (args, ctx) -> Math.floorDiv(l(args, 0), l(args, 1)));
        m.put("floorMod", (args, ctx) -> Math.floorMod(l(args, 0), l(args, 1)));
        return m;
    }

    private static BigInteger factorial(long n) {
        BigInteger r = BigInteger.ONE;
        for (long i = 2; i <= n; i++) r = r.multiply(BigInteger.valueOf(i));
        return r;
    }

    private static BigInteger choose(long n, long k) {
        if (k < 0 || k > n) return BigInteger.ZERO;
        return factorial(n).divide(factorial(k).multiply(factorial(n - k)));
    }

    private static long l(List<Object> args, int i) {
        return args.get(i) instanceof Long v ? v
                : args.get(i) instanceof Integer n ? n.longValue() : 0L;
    }

    private static BigInteger big(List<Object> args, int i) {
        return BigInteger.valueOf(l(args, i));
    }
}
