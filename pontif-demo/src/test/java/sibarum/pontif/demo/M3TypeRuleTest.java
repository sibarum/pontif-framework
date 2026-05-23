package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ast.binary.Add;
import sibarum.pontif.ast.literal.Bool;
import sibarum.pontif.ast.literal.IntLiteral;
import sibarum.pontif.core.Pontif;
import sibarum.pontif.core.types.RuleEngine;
import sibarum.pontif.core.types.RuleViolation;
import sibarum.pontif.core.types.Sort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M3TypeRuleTest {

    private static final Sort NAT = Sort.of("Nat");
    private static final Sort BOOL = Sort.of("Bool");

    private static RuleEngine arithmeticEngine() {
        return new RuleEngine()
                .register(IntLiteral.class, (n, kids, ctx) -> NAT)
                .register(Bool.class,       (n, kids, ctx) -> BOOL)
                .register(Add.class, (n, kids, ctx) -> {
                    if (!NAT.equals(kids.get(0)) || !NAT.equals(kids.get(1))) {
                        throw new RuleViolation(
                                "Add requires (Nat, Nat); got (" + kids.get(0) + ", " + kids.get(1) + ")");
                    }
                    return NAT;
                });
    }

    @Test
    void intLiteralChecksAsNat() throws Exception {
        assertEquals(NAT, arithmeticEngine().check(IntLiteral.of(5)));
    }

    @Test
    void boolLiteralChecksAsBool() throws Exception {
        assertEquals(BOOL, arithmeticEngine().check(Bool.of(true)));
    }

    @Test
    void addOfNatsChecksAsNat() throws Exception {
        assertEquals(NAT, arithmeticEngine().check(Add.of(IntLiteral.of(2), IntLiteral.of(3))));
    }

    @Test
    void nestedAddChecksAsNat() throws Exception {
        var inner = Add.of(IntLiteral.of(1), IntLiteral.of(2));
        var outer = Add.of(inner, IntLiteral.of(3));
        assertEquals(NAT, arithmeticEngine().check(outer));
    }

    @Test
    void addOfNatAndBoolViolatesRule() throws Exception {
        RuleViolation ex = assertThrows(
                RuleViolation.class,
                () -> arithmeticEngine().check(Add.of(IntLiteral.of(2), Bool.of(true))));
        assertTrue(
                ex.getMessage().contains("Nat") && ex.getMessage().contains("Bool"),
                "diagnostic should cite the conflicting sorts; got: " + ex.getMessage());
    }

    @Test
    void unregisteredNodeViolatesRule() throws Exception {
        RuleEngine bare = new RuleEngine();
        RuleViolation ex = assertThrows(
                RuleViolation.class,
                () -> bare.check(IntLiteral.of(5)));
        assertTrue(
                ex.getMessage().contains("IntLiteral"),
                "diagnostic should name the unregistered node class; got: " + ex.getMessage());
    }

    @Test
    void checkingDoesNotPreventEvaluation() throws Exception {
        var tree = Add.of(IntLiteral.of(2), IntLiteral.of(3));
        assertEquals(NAT, arithmeticEngine().check(tree));
        assertEquals(5L, Pontif.evalLong(tree));
    }
}
