package sibarum.pontif.predicates;

import sibarum.pontif.core.symbolic.RealInterval;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The strictness-aware real interval underlying the unified engine. Focused on
 * the two things the legacy integer {@link Interval} could not express and
 * that the Decimal domain needs: explicit endpoint <b>openness</b> ({@code >}
 * vs {@code >=}), and the way openness propagates through {@code add} /
 * {@code intersect} / {@code multiply}.
 */
class RealIntervalTest {

    private static BigDecimal d(String s) { return new BigDecimal(s); }
    private static RealInterval atLeast(String v, boolean incl) { return RealInterval.atLeast(d(v), incl); }
    private static RealInterval atMost(String v, boolean incl) { return RealInterval.atMost(d(v), incl); }

    // --- emptiness ----------------------------------------------------------

    @Test
    void openPointIsEmpty() {
        // (3, 3) holds no value
        assertTrue(new RealInterval(d("3"), false, d("3"), false).isEmpty());
    }

    @Test
    void closedPointIsNotEmpty() {
        assertFalse(RealInterval.point(d("3")).isEmpty());
    }

    @Test
    void reversedBoundsAreEmpty() {
        assertTrue(new RealInterval(d("5"), true, d("2"), true).isEmpty());
    }

    // --- add: openness infects ---------------------------------------------

    @Test
    void sumOfTwoOpenLowersIsOpen() {
        // (5, ∞) + (0, ∞) = (5, ∞)  — the x + this.x case
        RealInterval r = atLeast("5", false).add(atLeast("0", false));
        assertEquals(d("5"), r.lo());
        assertFalse(r.loIncl(), "open + open stays open");
        assertTrue(r.hiIsInfinite());
    }

    @Test
    void sumIsClosedOnlyWhenBothClosed() {
        RealInterval r = atLeast("2", true).add(atLeast("3", true));
        assertEquals(d("5"), r.lo());
        assertTrue(r.loIncl());
    }

    @Test
    void sumOfClosedAndOpenIsOpen() {
        RealInterval r = atLeast("2", true).add(atLeast("3", false));
        assertFalse(r.loIncl());
    }

    // --- intersect: tighter endpoint, exclusive wins on a tie ---------------

    @Test
    void intersectKeepsTheLargerLower() {
        RealInterval r = atLeast("2", true).intersect(atLeast("5", true));
        assertEquals(d("5"), r.lo());
    }

    @Test
    void intersectExclusiveWinsOnTie() {
        // [5, ∞) ∩ (5, ∞) = (5, ∞)
        RealInterval r = atLeast("5", true).intersect(atLeast("5", false));
        assertEquals(d("5"), r.lo());
        assertFalse(r.loIncl());
    }

    @Test
    void intersectDisjointIsEmpty() {
        assertTrue(atMost("1", true).intersect(atLeast("5", true)).isEmpty());
    }

    // --- scale --------------------------------------------------------------

    @Test
    void negativeScaleFlipsEndpointsAndKeepsStrictness() {
        // (2, ∞) · (−1) = (−∞, −2)
        RealInterval r = atLeast("2", false).scale(d("-1"));
        assertTrue(r.loIsInfinite());
        assertEquals(d("-2"), r.hi());
        assertFalse(r.hiIncl(), "strictness preserved under a nonzero scale");
    }

    @Test
    void scaleByZeroCollapsesToPointZero() {
        assertEquals(RealInterval.point(BigDecimal.ZERO), atLeast("7", false).scale(BigDecimal.ZERO));
    }

    // --- multiply -----------------------------------------------------------

    @Test
    void productOfPositiveRangesTakesCornerExtremes() {
        // [2, 5] · [3, 4] = [6, 20]
        RealInterval r = new RealInterval(d("2"), true, d("5"), true)
                .multiply(new RealInterval(d("3"), true, d("4"), true));
        assertEquals(d("6"), r.lo());
        assertEquals(d("20"), r.hi());
    }

    @Test
    void zeroEndpointTimesUpperUnboundedContributesZeroNotInfinity() {
        // [−2, 0] · [3, ∞) = (−∞, 0]  — the 0·∞ = 0 corner rule
        RealInterval r = new RealInterval(d("-2"), true, d("0"), true).multiply(atLeast("3", true));
        assertTrue(r.loIsInfinite());
        assertEquals(d("0"), r.hi());
    }

    @Test
    void productOfLowerBoundedPositivesRecoversMagnitude() {
        // [2, ∞) · [3, ∞) = [6, ∞)
        RealInterval r = atLeast("2", true).multiply(atLeast("3", true));
        assertEquals(d("6"), r.lo());
        assertTrue(r.hiIsInfinite());
    }
}
