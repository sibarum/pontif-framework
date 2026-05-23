package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ast.binary.Add;
import sibarum.pontif.ast.literal.IntLiteral;
import sibarum.pontif.core.Pontif;
import sibarum.pontif.core.types.RuleEngine;
import sibarum.pontif.core.types.RuleViolation;
import sibarum.pontif.demo.posnat.PosLit;
import sibarum.pontif.demo.posnat.PosNat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M4PosNatTest {

    @Test
    void posLitOfPositiveChecksAsPosNat() throws Exception {
        assertEquals(PosNat.SORT, PosNat.engine().check(PosLit.of(5)));
    }

    @Test
    void posLitOfZeroViolatesRule() throws Exception {
        RuleViolation ex = assertThrows(
                RuleViolation.class,
                () -> PosNat.engine().check(PosLit.of(0)));
        assertTrue(ex.getMessage().contains("positive"),
                "diagnostic should mention positivity; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("0"),
                "diagnostic should cite the offending value; got: " + ex.getMessage());
    }

    @Test
    void posLitOfNegativeViolatesRule() throws Exception {
        RuleViolation ex = assertThrows(
                RuleViolation.class,
                () -> PosNat.engine().check(PosLit.of(-3)));
        assertTrue(ex.getMessage().contains("-3"),
                "diagnostic should cite the offending value; got: " + ex.getMessage());
    }

    @Test
    void posLitEvaluatesLikeAnyOtherLiteral() throws Exception {
        assertEquals(7L, Pontif.evalLong(PosLit.of(7)));
    }

    @Test
    void addOfPositivesChecksAsPosNat() throws Exception {
        var tree = Add.of(PosLit.of(2), PosLit.of(3));
        assertEquals(PosNat.SORT, PosNat.engineWithAdd().check(tree));
        assertEquals(5L, Pontif.evalLong(tree));
    }

    @Test
    void addRejectsMixingPosNatAndIntLiteral() throws Exception {
        var tree = Add.of(PosLit.of(2), IntLiteral.of(3));
        assertThrows(RuleViolation.class, () -> PosNat.engineWithAdd().check(tree));
    }

    @Test
    void engineWithoutPosLitRule_cannotCheckPosLitTree() throws Exception {
        RuleEngine bare = new RuleEngine();
        RuleViolation ex = assertThrows(
                RuleViolation.class,
                () -> bare.check(PosLit.of(5)));
        assertTrue(ex.getMessage().contains("PosLit"),
                "diagnostic should name the unregistered node; got: " + ex.getMessage());
    }

    @Test
    void sameAddNode_typeChecksDifferentlyUnderDifferentEngines() throws Exception {
        var tree = Add.of(PosLit.of(2), PosLit.of(3));

        assertEquals(PosNat.SORT, PosNat.engineWithAdd().check(tree));

        RuleEngine onlyAddNoPosLit = new RuleEngine()
                .register(Add.class, (n, kids, ctx) -> PosNat.SORT);
        assertThrows(RuleViolation.class, () -> onlyAddNoPosLit.check(tree));
    }
}
