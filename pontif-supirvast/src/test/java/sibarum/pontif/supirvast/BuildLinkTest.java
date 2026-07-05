package sibarum.pontif.supirvast;

import dev.supirvast.vastir.core.BinaryOp;
import dev.supirvast.vastir.core.Buffer;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.tools.Accelerator;
import dev.supirvast.vastir.tools.KernelColumn;
import dev.supirvast.vastir.tools.KernelHandle;
import dev.supirvast.vastir.tools.Registration;
import dev.supirvast.vastir.tools.Rejection;
import dev.supirvast.vastir.type.Type;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice 0 — proves the cross-project build link and execution path resolve end to end, with a hand-written
 * SuperVast {@code core} kernel (the proven i32 vector-add). No Pontif lowering yet; this only de-risks the
 * Maven dependency on {@code dev.supirvast:*} and the GPU/CPU run path before the lowering logic lands.
 *
 * <p>Runs on the CPU (Truffle) backend when no Vulkan device is present, and on the GPU otherwise; the
 * differential check is requested but skipped (not failed) without a device, matching SuperVast's harness.
 */
final class BuildLinkTest {

    @Test
    void handWrittenVectorAddRunsThroughAccelerator() {
        // out[i] = a[i] + b[i], written directly in core (binding == slot: out=0, a=1, b=2).
        Buffer out = new Buffer("out", 0, Type.int32());
        Buffer a = new Buffer("a", 1, Type.int32());
        Buffer b = new Buffer("b", 2, Type.int32());
        Expr gid = new Expr.InvocationId();

        Function kernel = new Function("main",
                new Type.FunctionType(Type.VOID, List.of()),
                Region.of(
                        new Statement.BufferStore(out, gid,
                                new Expr.Binary(BinaryOp.ADD,
                                        new Expr.BufferLoad(a, gid),
                                        new Expr.BufferLoad(b, gid))),
                        new Statement.ReturnVoid()));

        var spec = new dev.supirvast.vastir.tools.KernelSpec(kernel, List.of(
                KernelColumn.output("out", 0, Type.int32()),
                KernelColumn.input("a", 1, Type.int32()),
                KernelColumn.input("b", 2, Type.int32())));

        try (Accelerator accelerator = new Accelerator()) {
            Registration registration = accelerator.register(spec);
            assertInstanceOf(KernelHandle.class, registration,
                    () -> "registration rejected: " + ((Rejection) registration).reason()
                            + " — " + ((Rejection) registration).detail());
            KernelHandle handle = (KernelHandle) registration;

            int n = 4;
            int[][] columns = {
                    new int[n],                 // out (zeroed)
                    {1, 2, 3, 4},               // a
                    {10, 20, 30, 40},           // b
            };

            int[][] result = handle.run(columns, n);
            assertArrayEquals(new int[]{11, 22, 33, 44}, result[0],
                    "vector-add result on " + handle.preferredBackend());

            KernelHandle.VerificationResult verification = handle.verify(columns, n);
            if (verification.verified()) {
                assertTrue(verification.matches(), verification.detail());
            }
        }
    }
}
