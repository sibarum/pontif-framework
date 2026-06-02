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
}
