package sibarum.pontif.core;

import java.math.BigDecimal;

/** Display formatting for {@code Decimal} runtime values. */
public final class Decimals {

    private Decimals() {}

    /**
     * Display form for a Decimal: plain notation (never scientific), always
     * with a decimal point so Decimal is visually distinct from Int
     * ({@code 10 → "10.0"}), and zeros normalized to {@code "0.0"} regardless
     * of the scale accumulated through arithmetic ({@code 0E-35 → "0.0"}).
     * Non-zero trailing scale is preserved ({@code 1.80} stays {@code 1.80} —
     * the scale is information).
     */
    public static String display(BigDecimal v) {
        if (v.compareTo(BigDecimal.ZERO) == 0) {
            return "0.0";
        }
        String s = v.toPlainString();
        return s.indexOf('.') >= 0 ? s : s + ".0";
    }

    /**
     * Approximate equality ({@code ~=}): equal within <b>one unit in the last
     * place at the working precision</b> (DECIMAL128, 34 significant digits),
     * scaled to the larger operand's magnitude.
     *
     * <p>The tolerance is <em>derived, not chosen</em>: it is exactly the loss
     * the language's own division policy declared, and nothing more. So
     * {@code 1.0/3.0*3.0 ~= 1.0} holds (the 34-nines artifact is within one
     * ulp of 1), while genuinely different values fail. Exactly-equal values
     * short-circuit, so {@code ~=} coincides with {@code ==} wherever no
     * rounding exists.
     *
     * <p>{@code x ~= 0} for nonzero {@code x} is {@code false}: relative
     * tolerance has no jurisdiction at zero — a tiny value cannot be assumed
     * to be a rounding artifact.
     */
    /**
     * The Decimal anatomy ({@code Decimal(unscaled:Decimal, scale:Int)} — the
     * native constructor's shape; this class is the single authority for its
     * projection half). Both projections are <b>total</b>:
     * {@code unscaled} is the canonical scale-0 integer-valued Decimal (never
     * an Int — the Int→Decimal embedding is a one-way wall), {@code scale} is
     * BigDecimal's 32-bit scale. Scale-0 values are the anatomy's
     * self-fixpoints: {@code x.unscaled.unscaled == x.unscaled}.
     */
    public static boolean isAnatomyField(String field) {
        return "unscaled".equals(field) || "scale".equals(field);
    }

    /** Canonical unscaled part — a scale-0 integer-valued Decimal. Total. */
    public static BigDecimal projectUnscaled(BigDecimal v) {
        return new BigDecimal(v.unscaledValue());
    }

    /** The scale part. Total. */
    public static long projectScale(BigDecimal v) {
        return v.scale();
    }

    public static boolean approxEqual(BigDecimal a, BigDecimal b) {
        if (a.compareTo(b) == 0) {
            return true;
        }
        BigDecimal diff = a.subtract(b).abs();
        BigDecimal mag = a.abs().max(b.abs());
        // Decimal exponent of the magnitude: precision - scale - 1.
        int exponent = mag.precision() - mag.scale() - 1;
        // 1 ulp at 34 significant digits of that magnitude.
        BigDecimal ulp = BigDecimal.ONE.scaleByPowerOfTen(exponent - 33);
        return diff.compareTo(ulp) <= 0;
    }
}
