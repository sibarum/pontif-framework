package sibarum.pontif.conservation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The covering relation is the ledger's prefix algebra: emitting a whole
 * aggregate conserves every attribute under it.
 */
class AttributePathTest {

    @Test
    void coversSelfAndDescendants_notSiblingsOrAncestors() {
        AttributePath whole = AttributePath.of("p_0");
        AttributePath x = whole.child("x");
        AttributePath deep = x.child("v");
        AttributePath y = whole.child("y");

        assertTrue(whole.covers(whole));
        assertTrue(whole.covers(x));
        assertTrue(whole.covers(deep));
        assertTrue(x.covers(deep));
        assertFalse(x.covers(y));
        assertFalse(x.covers(whole), "a child never covers its ancestor");
        assertFalse(AttributePath.of("q_0").covers(x), "different roots never cover");
    }

    @Test
    void rendering() {
        assertEquals("p_0.inner.v",
                new AttributePath("p_0", List.of("inner", "v")).toString());
        assertEquals("n_0", AttributePath.of("n_0").toString());
    }
}
