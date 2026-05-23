package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ast.literal.IntLiteral;
import sibarum.pontif.core.Pontif;

import static org.junit.jupiter.api.Assertions.assertEquals;

class M1IntLiteralTest {

    @Test
    void evaluatesPositiveLiteral() throws Exception {
        assertEquals(5L, Pontif.evalLong(IntLiteral.of(5)));
    }

    @Test
    void evaluatesNegativeLiteral() throws Exception {
        assertEquals(-42L, Pontif.evalLong(IntLiteral.of(-42)));
    }

    @Test
    void evaluatesZero() throws Exception {
        assertEquals(0L, Pontif.evalLong(IntLiteral.of(0)));
    }
}
