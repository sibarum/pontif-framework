package sibarum.pontif.predicates;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The range value type. Focused on {@link Interval#multiply} — the corner
 * cases (sign-straddling, unbounded factors, and especially {@code 0·∞})
 * are where soundness is won or lost.
 */
class IntervalTest {

    private static final long NEG_INF = Interval.NEG_INF;
    private static final long POS_INF = Interval.POS_INF;

    // --- multiply(): finite -------------------------------------------------

    @Test
    void productOfPositiveRanges() {
        // [2, 5] · [3, 4] = [6, 20]
        assertEquals(new Interval(6, 20), new Interval(2, 5).multiply(new Interval(3, 4)));
    }

    @Test
    void productStraddlingSignTakesCornerExtremes() {
        // [-2, 3] · [-4, 5]: corners {8, -10, -12, 15} → [-12, 15]
        assertEquals(new Interval(-12, 15), new Interval(-2, 3).multiply(new Interval(-4, 5)));
    }

    @Test
    void productOfTwoNegativesIsPositive() {
        // [-5, -2] · [-4, -3] = [6, 20]
        assertEquals(new Interval(6, 20), new Interval(-5, -2).multiply(new Interval(-4, -3)));
    }

    // --- multiply(): unbounded factors --------------------------------------

    @Test
    void productOfLowerBoundedPositives() {
        // [2, ∞) · [3, ∞) = [6, ∞)   — the x*y >= 6 magnitude win
        assertEquals(Interval.atLeast(6), Interval.atLeast(2).multiply(Interval.atLeast(3)));
    }

    @Test
    void productOfTwoUpperBoundedNegatives() {
        // (-∞, -9] · (-∞, -1] = [9, ∞)   — isSparse branch C
        assertEquals(Interval.atLeast(9), Interval.atMost(-9).multiply(Interval.atMost(-1)));
    }

    @Test
    void productOfNonNegativeAndLowerBounded() {
        // [0, ∞) · [8, ∞) = [0, ∞)   — isSparse branch A
        assertEquals(Interval.atLeast(0), Interval.atLeast(0).multiply(Interval.atLeast(8)));
    }

    @Test
    void squareOfWholeLineIsWholeLine() {
        // (-∞, ∞) · (-∞, ∞) = (-∞, ∞)   (sign analysis, not mult, recovers ≥0)
        assertEquals(Interval.all(), Interval.all().multiply(Interval.all()));
    }

    // --- multiply(): the 0·∞ corner (forced to 0) ---------------------------

    @Test
    void zeroTimesWholeLineIsZero() {
        // [0, 0] · (-∞, ∞) = [0, 0]   — the soundness-forcing case:
        // any other 0·∞ convention would exclude the real value 0.
        assertEquals(Interval.point(0), Interval.point(0).multiply(Interval.all()));
    }

    @Test
    void zeroEndpointTimesUpperUnbounded() {
        // [-2, 0] · [3, ∞) = (-∞, 0]   — corner 0·∞ contributes 0, not ±∞
        assertEquals(new Interval(NEG_INF, 0), new Interval(-2, 0).multiply(Interval.atLeast(3)));
    }

    @Test
    void zeroIsAbsorbingAcrossInfinities() {
        // every corner is 0·(±∞) = 0
        assertEquals(Interval.point(0), Interval.point(0).multiply(new Interval(NEG_INF, POS_INF)));
    }

    // --- multiply(): empties and overflow -----------------------------------

    @Test
    void emptyTimesAnythingIsEmpty() {
        assertTrue(Interval.empty().multiply(Interval.atLeast(3)).isEmpty());
        assertTrue(Interval.atLeast(3).multiply(Interval.empty()).isEmpty());
    }

    @Test
    void finiteOverflowSaturatesToSignedInfinity() {
        // huge · huge overflows long → saturates to +∞ (same sign)
        assertEquals(POS_INF, new Interval(3_000_000_000L, 4_000_000_000L)
                .multiply(new Interval(3_000_000_000L, 4_000_000_000L)).hi());
    }
}
