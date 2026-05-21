package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ast.literal.IntLiteral;
import sibarum.pontif.core.Pontif;

import static org.junit.jupiter.api.Assertions.assertEquals;

class M1IntLiteralTest {

    @Test
    void evaluatesPositiveLiteral() {
        assertEquals(5L, Pontif.evalLong(IntLiteral.of(5)));
    }

    @Test
    void evaluatesNegativeLiteral() {
        assertEquals(-42L, Pontif.evalLong(IntLiteral.of(-42)));
    }

    @Test
    void evaluatesZero() {
        assertEquals(0L, Pontif.evalLong(IntLiteral.of(0)));
    }
}
