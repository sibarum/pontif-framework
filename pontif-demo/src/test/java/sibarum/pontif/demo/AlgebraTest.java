package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.Algebra;
import sibarum.pontif.core.symbolic.algebra.BasisElement;
import sibarum.pontif.core.symbolic.algebra.Multivector;
import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.symbolic.algebra.Property;
import sibarum.pontif.demo.symbolic.algebra.Algebras;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlgebraTest {

    @Test
    void dualNumbersIsClosed() throws Exception {
        assertTrue(Algebras.dualNumbers().prove(Property.CLOSED).isPassed());
    }

    @Test
    void dualNumbersIsAssociative() throws Exception {
        assertTrue(Algebras.dualNumbers().prove(Property.ASSOCIATIVE).isPassed());
    }

    @Test
    void complexIsClosed() throws Exception {
        assertTrue(Algebras.complex().prove(Property.CLOSED).isPassed());
    }

    @Test
    void complexIsAssociative() throws Exception {
        assertTrue(Algebras.complex().prove(Property.ASSOCIATIVE).isPassed());
    }

    @Test
    void splitComplexIsClosed() throws Exception {
        assertTrue(Algebras.splitComplex().prove(Property.CLOSED).isPassed());
    }

    @Test
    void splitComplexIsAssociative() throws Exception {
        assertTrue(Algebras.splitComplex().prove(Property.ASSOCIATIVE).isPassed());
    }

    @Test
    void dualNumbers_twoPlusThreeEpsilon_squared() throws Exception {
        Algebra dn = Algebras.dualNumbers();
        Multivector x = Multivector.of(Map.of(
                "1", SymExpr.lit(2),
                "ε", SymExpr.lit(3)));
        Multivector squared = dn.multiply(x, x);
        Multivector expected = Multivector.of(Map.of(
                "1", SymExpr.lit(4),
                "ε", SymExpr.lit(12)));
        assertEquals(expected, squared);
    }

    @Test
    void complex_onePlusI_squared() throws Exception {
        Algebra c = Algebras.complex();
        Multivector x = Multivector.of(Map.of(
                "1", SymExpr.lit(1),
                "i", SymExpr.lit(1)));
        Multivector squared = c.multiply(x, x);
        Multivector expected = Multivector.of(Map.of(
                "i", SymExpr.lit(2)));
        assertEquals(expected, squared);
    }

    @Test
    void complex_iTimesI_isNegativeOne() throws Exception {
        Algebra c = Algebras.complex();
        Multivector i = Multivector.of(Map.of("i", SymExpr.lit(1)));
        Multivector product = c.multiply(i, i);
        Multivector expected = Multivector.of(Map.of("1", SymExpr.lit(-1)));
        assertEquals(expected, product);
    }

    @Test
    void splitComplex_jTimesJ_isOne() throws Exception {
        Algebra sc = Algebras.splitComplex();
        Multivector j = Multivector.of(Map.of("j", SymExpr.lit(1)));
        Multivector product = sc.multiply(j, j);
        Multivector expected = Multivector.of(Map.of("1", SymExpr.lit(1)));
        assertEquals(expected, product);
    }

    @Test
    void dualNumbers_epsilonSquared_isZero() throws Exception {
        Algebra dn = Algebras.dualNumbers();
        Multivector eps = Multivector.of(Map.of("ε", SymExpr.lit(1)));
        Multivector product = dn.multiply(eps, eps);
        assertEquals(Multivector.zero(), product);
    }

    @Test
    void incompleteCliffordBasis_isNotClosed() throws Exception {
        // Declare {1, i, j} but NOT ij — closure must fail because i · j
        // simplifies to a Mul(I, J) that isn't a basis element nor a scalar.
        Algebra incomplete = new Algebra(
                "IncompleteIJ",
                List.of(
                        new BasisElement("1", Algebras.ONE),
                        new BasisElement("i", Algebras.I),
                        new BasisElement("j", Algebras.J)),
                "1",
                Algebras.combinedSimplifier());

        ProofResult result = incomplete.prove(Property.CLOSED);
        assertFalse(result.isPassed());
        if (result instanceof ProofResult.Failed(String witness)) {
            assertTrue(witness.contains("i") && witness.contains("j"),
                    "witness should name the failing product; got: " + witness);
        }
    }

    @Test
    void derivedTable_dualNumbers_hasExpectedEntries() throws Exception {
        Map<String, Map<String, SymExpr>> table = Algebras.dualNumbers().derivedTable();
        assertEquals(SymExpr.lit(1),                                   table.get("1").get("1"));
        assertEquals(Algebras.EPSILON,                                  table.get("1").get("ε"));
        assertEquals(Algebras.EPSILON,                                  table.get("ε").get("1"));
        assertEquals(SymExpr.lit(0),                                    table.get("ε").get("ε"));
    }

    @Test
    void derivedTable_complex_hasExpectedEntries() throws Exception {
        Map<String, Map<String, SymExpr>> table = Algebras.complex().derivedTable();
        assertEquals(SymExpr.lit(1),  table.get("1").get("1"));
        assertEquals(Algebras.I,      table.get("1").get("i"));
        assertEquals(Algebras.I,      table.get("i").get("1"));
        assertEquals(SymExpr.lit(-1), table.get("i").get("i"));
    }
}
