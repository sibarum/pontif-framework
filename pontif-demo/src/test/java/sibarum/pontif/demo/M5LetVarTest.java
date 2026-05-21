package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ast.binary.Add;
import sibarum.pontif.ast.bind.Let;
import sibarum.pontif.ast.bind.Var;
import sibarum.pontif.ast.literal.IntLiteral;
import sibarum.pontif.core.Pontif;
import sibarum.pontif.core.UnboundVariableException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M5LetVarTest {

    @Test
    void planAcceptanceTest_letXEquals5InXPlus3() {
        var tree = Let.of("x",
                IntLiteral.of(5),
                Add.of(Var.of("x"), IntLiteral.of(3)));
        assertEquals(8L, Pontif.evalLong(tree));
    }

    @Test
    void letBindingReturnsBoundValueWhenBodyIsJustVar() {
        var tree = Let.of("x", IntLiteral.of(42), Var.of("x"));
        assertEquals(42L, Pontif.evalLong(tree));
    }

    @Test
    void nestedLetsWithIndependentNames() {
        var tree = Let.of("x", IntLiteral.of(1),
                Let.of("y", IntLiteral.of(2),
                        Add.of(Var.of("x"), Var.of("y"))));
        assertEquals(3L, Pontif.evalLong(tree));
    }

    @Test
    void shadowingPrefersInnermostBinding() {
        var tree = Let.of("x", IntLiteral.of(1),
                Let.of("x", IntLiteral.of(2),
                        Var.of("x")));
        assertEquals(2L, Pontif.evalLong(tree));
    }

    @Test
    void innerScopeDoesNotLeakToOuterReference() {
        var tree = Let.of("x", IntLiteral.of(1),
                Add.of(
                        Let.of("x", IntLiteral.of(2), Var.of("x")),
                        Var.of("x")));
        assertEquals(3L, Pontif.evalLong(tree));
    }

    @Test
    void letValueEvaluatedInOuterScope_notLetrec() {
        var tree = Let.of("x", IntLiteral.of(5),
                Let.of("x",
                        Add.of(Var.of("x"), IntLiteral.of(1)),
                        Var.of("x")));
        assertEquals(6L, Pontif.evalLong(tree));
    }

    @Test
    void unboundVariableRaisesDiagnostic() {
        UnboundVariableException ex = assertThrows(
                UnboundVariableException.class,
                () -> Pontif.evalLong(Var.of("undefined")));
        assertTrue(ex.getMessage().contains("undefined"),
                "diagnostic should name the unbound variable; got: " + ex.getMessage());
    }

    @Test
    void unboundVariableInsideExpressionStillCaught() {
        var tree = Add.of(IntLiteral.of(1), Var.of("ghost"));
        UnboundVariableException ex = assertThrows(
                UnboundVariableException.class,
                () -> Pontif.evalLong(tree));
        assertTrue(ex.getMessage().contains("ghost"),
                "diagnostic should name the unbound variable; got: " + ex.getMessage());
    }
}
