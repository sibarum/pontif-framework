package sibarum.pontif.gui;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.types.StringValue;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The string→AlgExpr arithmetic parser: precedence, implicit multiplication, unary minus, errors. */
class ExprParserTest {

    /** Canonical, fully-parenthesized rendering of an AlgExpr AST — makes precedence assertions exact. */
    private static String s(Object node) {
        if (!(node instanceof RecordValue r)) return String.valueOf(node);
        Object m0;
        return switch (r.typeName()) {
            case "pontif.algebra/Const" -> ((BigDecimal) r.members().get("value")).stripTrailingZeros().toPlainString();
            case "pontif.algebra/Param" -> ((StringValue) r.members().get("name")).content();
            case "pontif.algebra/Add" -> "(" + s(r.members().get("left")) + "+" + s(r.members().get("right")) + ")";
            case "pontif.algebra/Sub" -> "(" + s(r.members().get("left")) + "-" + s(r.members().get("right")) + ")";
            case "pontif.algebra/Mul" -> "(" + s(r.members().get("left")) + "*" + s(r.members().get("right")) + ")";
            case "pontif.algebra/Div" -> "(" + s(r.members().get("left")) + "/" + s(r.members().get("right")) + ")";
            case "pontif.algebra/Pow" -> "(" + s(r.members().get("base")) + "^" + s(r.members().get("exponent")) + ")";
            default -> "?";
        };
    }

    private static String parsed(String in) {
        Optional<RecordValue> r = ExprParser.parse(in);
        assertTrue(r.isPresent(), () -> "should parse: " + in);
        return s(r.get());
    }

    @Test
    void precedence_andAssociativity() {
        assertEquals("(2+(3*4))", parsed("2 + 3*4"), "* binds tighter than +");
        assertEquals("((2*x)+3)", parsed("2*x + 3"));
        assertEquals("((a/b)/c)", parsed("a/b/c"), "/ is left-associative");
        assertEquals("(x^(2^3))", parsed("x^2^3"), "^ is right-associative");
        assertEquals("(2*(x^3))", parsed("2*x^3"), "^ binds tighter than *");
    }

    @Test
    void implicitMultiplication() {
        assertEquals("(2*x)", parsed("2x"));
        assertEquals("((x+1)*(x-1))", parsed("(x+1)(x-1)"));
        assertEquals("(2*(x+1))", parsed("2(x+1)"));
        assertEquals("(x*y)", parsed("xy"), "each letter is its own variable");
        assertEquals("((7*(x^4))+3)", parsed("7x^4 + 3"), "coefficient · power");
    }

    @Test
    void unaryMinus_foldsIntoLiterals() {
        assertEquals("-5", parsed("-5"), "negated literal folds to a clean constant");
        assertEquals("(-5*x)", parsed("-5x"));
        assertEquals("(0-x)", parsed("-x"), "negated non-literal is 0 - x");
        assertEquals("(3-(2*x))", parsed("3 - 2x"));
    }

    @Test
    void theRationalFunction_parses() {
        assertEquals("(((2*x)+3)/(((x^2)+(3*x))-4))", parsed("(2x+3) / (x^2+3x-4)"));
    }

    @Test
    void invalidInput_returnsEmpty() {
        for (String bad : new String[]{"", "2x +", "(x", "x)", "2 @ 3", "*x", "x^", "2..3", "/"}) {
            assertTrue(ExprParser.parse(bad).isEmpty(), () -> "should reject: '" + bad + "'");
        }
    }
}
