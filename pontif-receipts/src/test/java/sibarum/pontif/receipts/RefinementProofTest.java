package sibarum.pontif.receipts;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.IrExpr;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the struct-literal → {@link Refinement} translator. The
 * end-to-end behavior is in {@code ProofAuthoringTest}; here we pin the
 * structural translation and the clear errors on malformed proof trees.
 */
class RefinementProofTest {

    private static final Map<String, SymExpr> RENAME = Map.of("x", SymExpr.var("x_0"));

    private static IrExpr leaf() {
        return new IrExpr.Record("Leaf", Map.of(), Origin.NONE);
    }

    private static IrExpr split(IrExpr pred, IrExpr whenTrue, IrExpr whenFalse) {
        Map<String, IrExpr> m = new LinkedHashMap<>();
        m.put("p", pred);
        m.put("whenTrue", whenTrue);
        m.put("whenFalse", whenFalse);
        return new IrExpr.Record("Split", m, Origin.NONE);
    }

    private static IrExpr ge(String var, long n) {
        return IrExpr.binOp(IrExpr.Op.GE, IrExpr.var(var), IrExpr.lit(n));
    }

    @Test
    void translatesAndRenamesToGraphVars() throws Exception {
        Refinement actual = RefinementProof.fromIr(split(ge("x", 1), leaf(), leaf()), RENAME);
        Refinement expected = Refinement.splitOn(
                SymExpr.cmp(SymExpr.var("x_0"), SymExpr.CmpOp.GE, SymExpr.lit(1)),
                Refinement.leaf(), Refinement.leaf());
        assertEquals(expected, actual, "predicate must be lifted to a Cmp and renamed x → x_0");
    }

    @Test
    void nonComparisonPredicate_rejected() {
        // p = x  (a bare variable, not a comparison)
        CompileException e = assertThrows(CompileException.class,
                () -> RefinementProof.fromIr(split(IrExpr.var("x"), leaf(), leaf()), RENAME));
        assertTrue(e.getMessage().contains("comparison"), () -> e.getMessage());
    }

    @Test
    void unknownConstructor_rejected() {
        IrExpr bad = new IrExpr.Record("Splitt", Map.of(), Origin.NONE);
        CompileException e = assertThrows(CompileException.class,
                () -> RefinementProof.fromIr(bad, RENAME));
        assertTrue(e.getMessage().contains("unknown proof constructor"), () -> e.getMessage());
    }

    @Test
    void nonRecord_rejected() {
        CompileException e = assertThrows(CompileException.class,
                () -> RefinementProof.fromIr(IrExpr.lit(0), RENAME));
        assertTrue(e.getMessage().contains("Leaf/Split struct tree"), () -> e.getMessage());
    }

    @Test
    void leafWithFields_rejected() {
        IrExpr badLeaf = new IrExpr.Record("Leaf", Map.of("x", IrExpr.lit(1)), Origin.NONE);
        CompileException e = assertThrows(CompileException.class,
                () -> RefinementProof.fromIr(badLeaf, RENAME));
        assertTrue(e.getMessage().contains("Leaf takes no fields"), () -> e.getMessage());
    }

    @Test
    void splitMissingField_rejected() {
        IrExpr badSplit = new IrExpr.Record("Split", Map.of("p", ge("x", 1)), Origin.NONE);
        CompileException e = assertThrows(CompileException.class,
                () -> RefinementProof.fromIr(badSplit, RENAME));
        assertTrue(e.getMessage().contains("missing field"), () -> e.getMessage());
    }
}
