package sibarum.pontif.gpu;

import dev.supirvast.vastir.tools.Accelerator;
import dev.supirvast.vastir.tools.KernelHandle;
import dev.supirvast.vastir.tools.KernelSpec;
import dev.supirvast.vastir.tools.Registration;
import dev.supirvast.vastir.tools.Rejection;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.supirvast.KernelLowering;
import sibarum.pontif.supirvast.ValueMarshaller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The native dispatch behind the {@code pontif.gpu} surface (docs/gpu-kernels.md) — runs a
 * data-parallel iteration as a SuperVast compute kernel on the GPU (SPIR-V / Vulkan via the
 * {@code Accelerator} facade), reusing the {@code pontif-supirvast} lowering.
 *
 * <p><b>Slice 1b — wiring spike.</b> One concrete kernel ({@code gpuVectorAdd}) proves the full
 * round trip end-to-end from a {@code .ptf}: Pontif {@code Stream[Int]} values → {@code i64}
 * columns → {@code KernelLowering} → {@code Accelerator.register}/{@code run} on the GPU →
 * columns → a Pontif {@code Stream[Int]}. Slice 1c generalizes this to the {@code … on Gpu}
 * directive over an arbitrary fragment (the iteration's IR reached through the fragment value).
 */
public final class GpuKernels {

    private GpuKernels() {}

    private static final Origin O = Origin.NONE;

    /** Native {@code gpuVectorAdd(a, b):Stream[Int]} — element-wise a+b, run on the GPU. */
    public static Object vectorAdd(List<Object> args, NativeCalls.Context ctx) {
        long[] a = longs(args.get(0), "a");
        long[] b = longs(args.get(1), "b");
        int n = Math.min(a.length, b.length);            // zip stops at the shortest
        KernelSpec spec = new KernelLowering().lower(vectorAddIterate());
        try (Accelerator accelerator = new Accelerator()) {
            Registration registration = accelerator.register(spec);
            if (!(registration instanceof KernelHandle handle)) {
                Rejection rejection = (Rejection) registration;
                throw new RuntimeException("gpuVectorAdd: the GPU rejected the kernel — "
                        + rejection.reason() + ": " + rejection.detail());
            }
            int[][] columns = new int[3][];
            columns[0] = ValueMarshaller.outputColumn(n);            // slot 0 = output
            columns[1] = ValueMarshaller.toColumn(prefix(a, n));
            columns[2] = ValueMarshaller.toColumn(prefix(b, n));
            int[][] result = handle.run(columns, n);
            return stream(ValueMarshaller.fromColumn(result[0], n));
        }
    }

    /**
     * The {@code Iterate} for {@code (&a, &b):[(x, y) -> x + y]} — one STREAM output, one wildcard
     * arm, body {@code element._0 + element._1}. Matches what {@code AltParser.lowerZip} emits, so
     * the real {@code KernelLowering} path is exercised (the source var names are placeholders —
     * data is supplied positionally as columns at {@code run}).
     */
    static IrExpr.Iterate vectorAddIterate() {
        String e = "$e0";
        IrExpr body = new IrExpr.BinOp(IrExpr.Op.ADD,
                new IrExpr.FieldAccess(new IrExpr.Var(e, O), "_0", O),
                new IrExpr.FieldAccess(new IrExpr.Var(e, O), "_1", O), O);
        IrExpr.OutputSpec out = new IrExpr.OutputSpec("default", IrExpr.OutputKind.STREAM, null);
        IrExpr.Arm arm = new IrExpr.Arm(IrSort.named("_"), List.of(new IrExpr.Write("default", null, body)));
        return new IrExpr.Iterate(new IrExpr.Var("a", O), List.of(new IrExpr.Var("b", O)),
                e, List.of(out), List.of(arm), O);
    }

    /** A Pontif {@code Stream[Int]} value's elements as {@code long}s (its members are Int scalars). */
    static long[] longs(Object streamValue, String which) {
        if (!(streamValue instanceof RecordValue rv)) {
            throw new RuntimeException("gpuVectorAdd: argument '" + which + "' must be a Stream[Int]; got "
                    + (streamValue == null ? "null" : streamValue.getClass().getSimpleName()));
        }
        List<Long> xs = new ArrayList<>();
        for (Object m : rv.members().values()) {
            if (m instanceof Long l) xs.add(l);
            else if (m instanceof Integer i) xs.add(i.longValue());
            else throw new RuntimeException("gpuVectorAdd: stream '" + which + "' has a non-Int element " + m);
        }
        long[] out = new long[xs.size()];
        for (int i = 0; i < out.length; i++) out[i] = xs.get(i);
        return out;
    }

    /** Whether {@code streamValue}'s elements are Decimals (a float kernel) rather than Ints. Empty ⇒ false. */
    static boolean isDecimalStream(Object streamValue) {
        return streamValue instanceof RecordValue rv
                && rv.members().values().stream().findFirst().orElse(null) instanceof java.math.BigDecimal;
    }

    /** A Pontif {@code Stream[Decimal]} value's elements as doubles (lowered to f32 downstream — lossy). */
    static double[] decimals(Object streamValue, String which) {
        if (!(streamValue instanceof RecordValue rv)) {
            throw new RuntimeException("`… on Gpu`: source '" + which + "' must be a Stream[Decimal]; got "
                    + (streamValue == null ? "null" : streamValue.getClass().getSimpleName()));
        }
        List<Double> xs = new ArrayList<>();
        for (Object m : rv.members().values()) {
            if (m instanceof java.math.BigDecimal d) xs.add(d.doubleValue());
            else if (m instanceof Long l) xs.add((double) l);       // an Int promotes into a Decimal kernel
            else throw new RuntimeException("`… on Gpu`: stream '" + which + "' has a non-Decimal element " + m);
        }
        double[] out = new double[xs.size()];
        for (int i = 0; i < out.length; i++) out[i] = xs.get(i);
        return out;
    }

    static long[] prefix(long[] a, int n) {
        long[] out = new long[n];
        System.arraycopy(a, 0, out, 0, n);
        return out;
    }

    static double[] prefix(double[] a, int n) {
        double[] out = new double[n];
        System.arraycopy(a, 0, out, 0, n);
        return out;
    }

    /** Wraps a result array as a Pontif stream value (a {@code _tuple} of {@code _0.._n-1} → Long). */
    static RecordValue stream(long[] xs) {
        Map<String, Object> members = new LinkedHashMap<>();
        for (int i = 0; i < xs.length; i++) members.put("_" + i, xs[i]);
        return new RecordValue("_tuple", members);
    }
}
