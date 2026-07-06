package sibarum.pontif.supirvast;

/**
 * Marshals Pontif {@code Int} values (Java {@code long}) to and from SuperVast's {@code int[]} column wire
 * format. A 64-bit element occupies two little-endian words — {@code word[2i]} the low 32 bits,
 * {@code word[2i+1]} the high 32 — matching the i64 buffer encoding in {@code dev.supirvast.vast.CoreToTruffle}
 * (and the GPU's std430 layout). This is the honest boundary: a Pontif {@code Int} is 64-bit, so it crosses as
 * 64 bits, never narrowed to a 32-bit word.
 */
public final class ValueMarshaller {

    private ValueMarshaller() {}

    /** A column of {@code values.length} {@code int64} elements as {@code 2 * length} little-endian words. */
    public static int[] toColumn(long[] values) {
        int[] words = new int[values.length * 2];
        for (int i = 0; i < values.length; i++) {
            words[2 * i] = (int) values[i];
            words[2 * i + 1] = (int) (values[i] >>> 32);
        }
        return words;
    }

    /** A zeroed output column sized for {@code n} {@code int64} elements. */
    public static int[] outputColumn(int n) {
        return new int[n * 2];
    }

    /** Decodes the first {@code n} {@code int64} elements from a column's words. */
    public static long[] fromColumn(int[] words, int n) {
        long[] values = new long[n];
        for (int i = 0; i < n; i++) {
            values[i] = (words[2 * i] & 0xFFFFFFFFL) | ((long) words[2 * i + 1] << 32);
        }
        return values;
    }

    // --- f32 columns (Pontif Decimal on the GPU) ---------------------------------------------------------
    // A Pontif Decimal lowers to IEEE f32 for GPU compute — a lossy cast, ruled acceptable (Decimal is the
    // generic real type, James 2026-07-06). One word per element (std430 f32 layout); the bit pattern
    // crosses, decoded back to a Decimal at f32 precision.

    /** A column of {@code values.length} f32 elements (one word each), from Pontif Decimals (lossy). */
    public static int[] toColumnF32(double[] values) {
        int[] words = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            words[i] = Float.floatToRawIntBits((float) values[i]);
        }
        return words;
    }

    /** A zeroed output column sized for {@code n} f32 elements. */
    public static int[] outputColumnF32(int n) {
        return new int[n];
    }

    /** Decodes the first {@code n} f32 elements from a column's words (as doubles at f32 precision). */
    public static double[] fromColumnF32(int[] words, int n) {
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            values[i] = Float.intBitsToFloat(words[i]);
        }
        return values;
    }
}
