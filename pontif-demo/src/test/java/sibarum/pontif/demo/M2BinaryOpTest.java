package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ast.binary.Add;
import sibarum.pontif.ast.literal.IntLiteral;
import sibarum.pontif.core.Pontif;

import static org.junit.jupiter.api.Assertions.assertEquals;

class M2BinaryOpTest {

    @Test
    void addsPositiveLiterals() throws Exception {
        assertEquals(5L, Pontif.evalLong(Add.of(IntLiteral.of(2), IntLiteral.of(3))));
    }

    @Test
    void addsNegativeAndPositive() throws Exception {
        assertEquals(-1L, Pontif.evalLong(Add.of(IntLiteral.of(-4), IntLiteral.of(3))));
    }

    @Test
    void nestsAdditionsToProveRecursion() throws Exception {
        var inner1 = Add.of(IntLiteral.of(1), IntLiteral.of(2));
        var inner2 = Add.of(IntLiteral.of(3), IntLiteral.of(4));
        var outer = Add.of(inner1, inner2);
        assertEquals(10L, Pontif.evalLong(outer));
    }

    @Test
    void leftLeaningChainSums() throws Exception {
        var chain = Add.of(Add.of(Add.of(IntLiteral.of(1), IntLiteral.of(2)), IntLiteral.of(3)), IntLiteral.of(4));
        assertEquals(10L, Pontif.evalLong(chain));
    }
}
