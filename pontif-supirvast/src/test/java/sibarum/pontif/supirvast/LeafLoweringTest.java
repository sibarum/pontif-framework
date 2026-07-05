package sibarum.pontif.supirvast;

import dev.supirvast.vastir.core.BinaryOp;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.core.UnaryOp;
import dev.supirvast.vastir.type.Type;
import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrSort;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Slice 1 — leaf lowering of the Int-only subset, plus the fail-closed witnesses for everything else. */
final class LeafLoweringTest {

    private final ExprLowering exprs = new ExprLowering();
    private final SortLowering sorts = new SortLowering();

    // --- SortLowering --------------------------------------------------------------------------------

    @Test
    void intLowersToInt64_andBoolToBool() {
        assertEquals(Type.int64(), sorts.lowerScalar(IrSort.named("Int")));
        assertEquals(Type.BOOL, sorts.lowerScalar(IrSort.named("Bool")));
    }

    @Test
    void refinedIntLowersToItsBaseMachineType() {
        IrSort refined = IrSort.refined("Int", IrExpr.binOp(IrExpr.Op.GT, IrExpr.var("@"), IrExpr.lit(0)));
        assertEquals(Type.int64(), sorts.lowerScalar(refined));
    }

    @Test
    void decimalSortFailsClosedWithName() {
        LoweringError e = assertThrows(LoweringError.class, () -> sorts.lowerScalar(IrSort.named("Decimal")));
        assertTrue(e.getMessage().contains("Decimal"), e.getMessage());
    }

    @Test
    void aggregateSortsFailClosed() {
        assertThrows(LoweringError.class,
                () -> sorts.lowerScalar(IrSort.structural("Point", java.util.Map.of("x", IrSort.named("Int")))));
        assertThrows(LoweringError.class,
                () -> sorts.lowerScalar(new IrSort.Named("Array", List.of(IrSort.named("Int")), Origin.NONE)));
    }

    // --- ExprLowering: supported ---------------------------------------------------------------------

    @Test
    void intLiteralLowersToConstInt64() {
        ExprLowering.Block block = exprs.lower(IrExpr.lit(42), Scope.empty());
        assertTrue(block.statements().isEmpty());
        Expr.ConstInt c = assertInstanceOf(Expr.ConstInt.class, block.value());
        assertEquals(42L, c.value());
        assertEquals(Type.int64(), c.type());
    }

    @Test
    void arithmeticLowersToBinary() {
        IrExpr sum = IrExpr.binOp(IrExpr.Op.ADD, IrExpr.lit(2), IrExpr.lit(3));
        Expr.Binary b = assertInstanceOf(Expr.Binary.class, exprs.lower(sum, Scope.empty()).value());
        assertEquals(BinaryOp.ADD, b.op());
    }

    @Test
    void notEqualDesugarsToNegatedEqual() {
        IrExpr ne = IrExpr.binOp(IrExpr.Op.NE, IrExpr.lit(1), IrExpr.lit(2));
        Expr.Unary u = assertInstanceOf(Expr.Unary.class, exprs.lower(ne, Scope.empty()).value());
        assertEquals(UnaryOp.LOGICAL_NOT, u.op());
        assertEquals(BinaryOp.EQUAL, assertInstanceOf(Expr.Binary.class, u.operand()).op());
    }

    @Test
    void lessEqualAndGreaterEqualDesugar() {
        Expr le = exprs.lower(IrExpr.binOp(IrExpr.Op.LE, IrExpr.lit(1), IrExpr.lit(2)), Scope.empty()).value();
        assertEquals(BinaryOp.GREATER_THAN, assertInstanceOf(Expr.Binary.class,
                assertInstanceOf(Expr.Unary.class, le).operand()).op());
        Expr ge = exprs.lower(IrExpr.binOp(IrExpr.Op.GE, IrExpr.lit(1), IrExpr.lit(2)), Scope.empty()).value();
        assertEquals(BinaryOp.LESS_THAN, assertInstanceOf(Expr.Binary.class,
                assertInstanceOf(Expr.Unary.class, ge).operand()).op());
    }

    @Test
    void letBindingDeclaresLocalAndBodyReadsIt() {
        // let x = 10 in x + 5
        IrExpr body = IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.lit(5));
        IrExpr let = IrExpr.letIn("x", null, IrExpr.lit(10), body);

        ExprLowering.Block block = exprs.lower(let, Scope.empty());
        assertEquals(1, block.statements().size());
        Statement.DeclareVar decl = assertInstanceOf(Statement.DeclareVar.class, block.statements().get(0));
        assertEquals("x", decl.variable().name());
        assertEquals(Type.int64(), decl.variable().type());

        // The body's `x` reads exactly the declared local (identity by reference).
        Expr.Binary add = assertInstanceOf(Expr.Binary.class, block.value());
        Expr.Read read = assertInstanceOf(Expr.Read.class, add.lhs());
        assertSame(decl.variable(), read.variable());
    }

    @Test
    void unboundVariableFailsClosed() {
        LoweringError e = assertThrows(LoweringError.class, () -> exprs.lower(IrExpr.var("ghost"), Scope.empty()));
        assertTrue(e.getMessage().contains("ghost"), e.getMessage());
    }

    // --- ExprLowering: every unsupported variant has a witness --------------------------------------

    @Test
    void decimalLiteralFailsClosed() {
        assertThrows(LoweringError.class, () -> exprs.lower(IrExpr.dec(new BigDecimal("1.5")), Scope.empty()));
    }

    @Test
    void powAndApproxOperatorsFailClosed() {
        assertThrows(LoweringError.class,
                () -> exprs.lower(IrExpr.binOp(IrExpr.Op.POW, IrExpr.lit(2), IrExpr.lit(8)), Scope.empty()));
        assertThrows(LoweringError.class,
                () -> exprs.lower(IrExpr.binOp(IrExpr.Op.APPROX, IrExpr.lit(1), IrExpr.lit(1)), Scope.empty()));
    }

    @Test
    void charStringCastRecordFailClosed() {
        assertThrows(LoweringError.class, () -> exprs.lower(IrExpr.chr('a'), Scope.empty()));
        assertThrows(LoweringError.class, () -> exprs.lower(IrExpr.str("hi"), Scope.empty()));
        assertThrows(LoweringError.class,
                () -> exprs.lower(IrExpr.cast(IrSort.named("String"), IrExpr.lit(1)), Scope.empty()));
        assertThrows(LoweringError.class,
                () -> exprs.lower(IrExpr.record("Point", java.util.Map.of("x", IrExpr.lit(1))), Scope.empty()));
    }

    @Test
    void errorCarriesSourceOrigin() {
        Origin where = Origin.at("kernel.ptf", 7, 3);
        IrExpr.Dec dec = new IrExpr.Dec(new BigDecimal("2.0"), where);
        LoweringError e = assertThrows(LoweringError.class, () -> exprs.lower(dec, Scope.empty()));
        assertSame(where, e.origin());
        assertTrue(e.getMessage().contains("kernel.ptf:7:3"), e.getMessage());
    }
}
