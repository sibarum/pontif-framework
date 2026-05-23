package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingletonTest {

    @Test
    void singleton_selfEqualsLit_inferLit() throws Exception {
        Sort one = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.EQ, SymExpr.lit(1)));
        assertEquals(Optional.of(SymExpr.lit(1)), Refinements.uniqueValue(one));
    }

    @Test
    void singleton_litEqualsSelf_inferLit() throws Exception {
        // Cmp(Lit(7), EQ, Self) — symmetric form
        Sort seven = Sort.refined("Int",
                SymExpr.cmp(SymExpr.lit(7), SymExpr.CmpOp.EQ, SymExpr.self()));
        assertEquals(Optional.of(SymExpr.lit(7)), Refinements.uniqueValue(seven));
    }

    @Test
    void singleton_zero_works() throws Exception {
        Sort zero = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.EQ, SymExpr.lit(0)));
        assertEquals(Optional.of(SymExpr.lit(0)), Refinements.uniqueValue(zero));
    }

    @Test
    void singleton_negative_works() throws Exception {
        Sort negThree = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.EQ, SymExpr.lit(-3)));
        assertEquals(Optional.of(SymExpr.lit(-3)), Refinements.uniqueValue(negThree));
    }

    @Test
    void singleton_fracLiteral_works() throws Exception {
        Sort half = Sort.refined("Rational",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.EQ, SymExpr.frac(1, 2)));
        assertEquals(Optional.of(SymExpr.frac(1, 2)), Refinements.uniqueValue(half));
    }

    @Test
    void unrefinedSort_hasNoUniqueValue() throws Exception {
        assertTrue(Refinements.uniqueValue(Sort.of("Int")).isEmpty());
    }

    @Test
    void rangeRefinement_hasNoUniqueValue() throws Exception {
        Sort positive = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)));
        assertTrue(Refinements.uniqueValue(positive).isEmpty());
    }

    @Test
    void inequalityRefinement_hasNoUniqueValue() throws Exception {
        Sort notFive = Sort.refined("Int",
                SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.NE, SymExpr.lit(5)));
        assertTrue(Refinements.uniqueValue(notFive).isEmpty());
    }
}
