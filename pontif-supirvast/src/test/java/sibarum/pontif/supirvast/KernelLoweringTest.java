package sibarum.pontif.supirvast;

import dev.supirvast.vastir.tools.Accelerator;
import dev.supirvast.vastir.tools.KernelHandle;
import dev.supirvast.vastir.tools.KernelSpec;
import dev.supirvast.vastir.tools.Registration;
import dev.supirvast.vastir.tools.Rejection;
import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrSort;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice 2 — lowering the stream {@link IrExpr.Iterate} construct to a SuperVast kernel and running it.
 * Streams unblocked this: {@code (&a, &b):[(x,y) -> x+y]} (zip) is vector-add, the motivating GPU kernel.
 *
 * <p>The {@code Iterate} values here are built to match exactly what {@code AltParser.lowerSpreadCall}
 * emits for spread/zip, so the lowering is exercised against the real construct shape, not a bespoke one.
 */
final class KernelLoweringTest {

    private static final Origin O = Origin.NONE;
    private final KernelLowering lowering = new KernelLowering();

    /** The Iterate for {@code (&a, &b):[(x,y) -> x + y]} — vector-add (one STREAM output, wildcard arm). */
    private static IrExpr.Iterate vectorAdd() {
        String e = "$e0";
        IrExpr body = new IrExpr.BinOp(IrExpr.Op.ADD,
                new IrExpr.FieldAccess(new IrExpr.Var(e, O), "_0", O),
                new IrExpr.FieldAccess(new IrExpr.Var(e, O), "_1", O), O);
        IrExpr.OutputSpec out = new IrExpr.OutputSpec("default", IrExpr.OutputKind.STREAM, null);
        IrExpr.Arm arm = new IrExpr.Arm(IrSort.named("_"), List.of(new IrExpr.Write("default", null, body)));
        return new IrExpr.Iterate(new IrExpr.Var("a", O), List.of(new IrExpr.Var("b", O)),
                e, List.of(out), List.of(arm), O);
    }

    /** The Iterate for {@code &s:[x -> x * x]} — single-source map (squares). */
    private static IrExpr.Iterate square() {
        String e = "$e1";
        IrExpr body = new IrExpr.BinOp(IrExpr.Op.MUL, new IrExpr.Var(e, O), new IrExpr.Var(e, O), O);
        IrExpr.OutputSpec out = new IrExpr.OutputSpec("default", IrExpr.OutputKind.STREAM, null);
        IrExpr.Arm arm = new IrExpr.Arm(IrSort.named("_"), List.of(new IrExpr.Write("default", null, body)));
        return new IrExpr.Iterate(new IrExpr.Var("s", O), e, List.of(out), List.of(arm), O);
    }

    private static long[] runKernel(KernelSpec spec, int n, int[]... inputColumns) {
        try (Accelerator accelerator = new Accelerator()) {
            Registration registration = accelerator.register(spec);
            assertInstanceOf(KernelHandle.class, registration,
                    () -> "registration rejected: " + ((Rejection) registration).reason()
                            + " — " + ((Rejection) registration).detail());
            KernelHandle handle = (KernelHandle) registration;

            int[][] columns = new int[inputColumns.length + 1][];
            columns[0] = ValueMarshaller.outputColumn(n);       // slot 0 = output
            System.arraycopy(inputColumns, 0, columns, 1, inputColumns.length);

            int[][] result = handle.run(columns, n);
            return ValueMarshaller.fromColumn(result[0], n);
        }
    }

    @Test
    void zipLowersToVectorAddKernel() {
        int n = 4;
        long[] result = runKernel(lowering.lower(vectorAdd()), n,
                ValueMarshaller.toColumn(new long[]{1, 2, 3, 4}),
                ValueMarshaller.toColumn(new long[]{10, 20, 30, 40}));
        assertArrayEquals(new long[]{11, 22, 33, 44}, result);
    }

    @Test
    void zipUsesHonest64BitElements() {
        // Operands beyond 32 bits must survive — proof the boundary is int64, not a narrowed int32.
        int n = 2;
        long big = 5_000_000_000L;                              // > 2^32
        long[] result = runKernel(lowering.lower(vectorAdd()), n,
                ValueMarshaller.toColumn(new long[]{big, 1}),
                ValueMarshaller.toColumn(new long[]{big, 2}));
        assertArrayEquals(new long[]{2 * big, 3}, result);
    }

    @Test
    void mapLowersToElementwiseKernel() {
        int n = 4;
        long[] result = runKernel(lowering.lower(square()), n,
                ValueMarshaller.toColumn(new long[]{1, 2, 3, 4}));
        assertArrayEquals(new long[]{1, 4, 9, 16}, result);
    }

    // --- fail-closed: shapes outside map/zip are rejected with a source-located witness ---------------

    @Test
    void foldShapeIsRejected() {
        // An ACCUMULATOR output (fold/scan) is not a data-parallel map — rejected, not miscompiled.
        IrExpr.OutputSpec acc = new IrExpr.OutputSpec("_0", IrExpr.OutputKind.ACCUMULATOR, new IrExpr.Lit(0, O));
        IrExpr.Arm arm = new IrExpr.Arm(IrSort.named("_"),
                List.of(new IrExpr.Write("_0", null, new IrExpr.Var("$e", O))));
        IrExpr.Iterate fold = new IrExpr.Iterate(new IrExpr.Var("s", O), "$e", List.of(acc), List.of(arm), O);
        LoweringError error = assertThrows(LoweringError.class, () -> lowering.lower(fold));
        assertTrue(error.reason().contains("ACCUMULATOR") || error.reason().contains("STREAM"),
                () -> "expected an accumulator-shape rejection; got: " + error.reason());
    }

    @Test
    void guardedMultiArmBodyIsRejected() {
        // Two arms = a guarded body (filter/takeWhile) — no unconditional data-parallel form in v1.
        IrExpr.OutputSpec out = new IrExpr.OutputSpec("default", IrExpr.OutputKind.STREAM, null);
        IrExpr.Arm a1 = new IrExpr.Arm(IrSort.refined("Int",
                new IrExpr.BinOp(IrExpr.Op.GT, new IrExpr.SelfRef(O), new IrExpr.Lit(0, O), O)),
                List.of(new IrExpr.Write("default", null, new IrExpr.Var("$e", O))));
        IrExpr.Arm a2 = new IrExpr.Arm(IrSort.named("_"),
                List.of(new IrExpr.Write("default", null, new IrExpr.Var("$e", O))));
        IrExpr.Iterate guarded = new IrExpr.Iterate(new IrExpr.Var("s", O), "$e", List.of(out), List.of(a1, a2), O);
        LoweringError error = assertThrows(LoweringError.class, () -> lowering.lower(guarded));
        assertTrue(error.reason().contains("arms") || error.reason().contains("guard"),
                () -> "expected a multi-arm/guard rejection; got: " + error.reason());
    }
}
