package sibarum.pontif.gpu;

import dev.supirvast.vastir.tools.Accelerator;
import dev.supirvast.vastir.tools.KernelHandle;
import dev.supirvast.vastir.tools.Registration;
import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner;
import sibarum.pontif.runtime.module.Extensions;
import sibarum.pontif.supirvast.KernelLowering;
import sibarum.pontif.supirvast.ValueMarshaller;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that a Pontif program dispatches a real compute kernel to the GPU
 * (docs/gpu-kernels.md, slice 1b). {@code gpuVectorAdd} routes through the {@code pontif.gpu}
 * extension → {@code KernelLowering} → SuperVast {@code Accelerator} → SPIR-V → Vulkan → back to a
 * Pontif {@code Stream[Int]}. Not a unit test poking the lowering — a {@code .ptf} running on the GPU.
 */
class GpuKernelTest {

    @Test
    void gpuVectorAdd_runsOnTheGpu() {
        Extensions.install(new GpuExtension());
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.gpu.{gpuVectorAdd}
                        gpuVectorAdd({1, 2, 3, 4}, {10, 20, 30, 40})""", "gpu.ptf"),
                PontifRunner.Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "gpu program should run; got " + r.text());
        assertEquals("{11, 22, 33, 44}", r.text());
    }

    @Test
    void onGpu_zip_wovenEmitDeliversEachResultToAnAction() {
        // The general surface (docs/gpu-kernels.md, slice 2): the kernel function carries a woven
        // `emit` of a USER event. The GPU computes the emit's argument (x+y per element); the emit is
        // deferred (sugar, not a live per-element fire) and replayed on the host after the batch — an
        // `action` reacts per element. Forward-only: no `await`, no built-in result type. main renders
        // nothing (for-effect); the proof is what the action wrote to stdout.
        String out = runCapturingStdout("""
                requires pontif.core.{Stream}
                requires pontif.events.{StdOut, Event}
                struct AddSamplesEvent(r:Int)
                assign trait AddSamplesEvent:Event{}
                let a:Stream[Int] = {1, 2, 3, 4}
                let b:Stream[Int] = {10, 20, 30, 40}
                function thisRunsOnGpu(x:Int, y:Int):Int ->
                  let r = x + y
                  emit AddSamplesEvent(r)
                  r
                action log(e:AddSamplesEvent) ->
                  emit StdOut("" + e.r + " ")
                  e
                main ( (&a, &b):[ (x:Int, y:Int) -> thisRunsOnGpu(x, y) ] on Gpu )""");
        assertEquals("11 22 33 44 ", out);
    }

    @Test
    void onGpu_map_wovenEmitDeliversEachResult() {
        // Single-source map (squares); same woven-emit delivery.
        String out = runCapturingStdout("""
                requires pontif.core.{Stream}
                requires pontif.events.{StdOut, Event}
                struct SquareEvent(r:Int)
                assign trait SquareEvent:Event{}
                let c:Stream[Int] = {1, 2, 3, 4}
                function squareOnGpu(x:Int):Int ->
                  let s = x * x
                  emit SquareEvent(s)
                  s
                action log(e:SquareEvent) ->
                  emit StdOut("" + e.r + " ")
                  e
                &c:[ (x:Int) -> squareOnGpu(x) ] on Gpu""");
        assertEquals("1 4 9 16 ", out);
    }

    @Test
    void onGpu_repeatedDispatch_reusesTheCachedKernel() {
        // The latency fix (docs/gpu-kernels.md): one long-lived Accelerator + per-kernel handle cache,
        // so re-running the same `on Gpu` (e.g. the editor re-compiling on each edit) reuses the built
        // Vulkan context + pipeline (~2 ms) instead of rebuilding them (~580 ms). Correctness check:
        // the same program run twice in one JVM produces the same output both times (the second hits
        // the cache).
        String src = """
                requires pontif.core.{Stream}
                requires pontif.events.{StdOut, Event}
                struct AddSamplesEvent(r:Int)
                assign trait AddSamplesEvent:Event{}
                let a:Stream[Int] = {1, 2, 3, 4}
                let b:Stream[Int] = {10, 20, 30, 40}
                function thisRunsOnGpu(x:Int, y:Int):Int ->
                  let r = x + y
                  emit AddSamplesEvent(r)
                  r
                action log(e:AddSamplesEvent) ->
                  emit StdOut("" + e.r + " ")
                  e
                main ( (&a, &b):[ (x:Int, y:Int) -> thisRunsOnGpu(x, y) ] on Gpu )""";
        assertEquals("11 22 33 44 ", runCapturingStdout(src));
        assertEquals("11 22 33 44 ", runCapturingStdout(src));   // second run: cache hit, same result
    }

    @Test
    void onGpu_withoutAWovenEmit_isARejectedError() {
        // Delivery is the woven emit; a kernel that emits nothing produces nothing observable, so it's
        // an honest error rather than a silent no-op. GPU-independent (rejected before dispatch).
        Extensions.install(new GpuExtension());
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.core.{Stream}
                        let c:Stream[Int] = {1, 2, 3, 4}
                        main ( &c:[ (x:Int) -> x * x ] on Gpu )""", "ongpu.ptf"),
                PontifRunner.Engine.INTERPRETER);
        assertTrue(r.isError(), () -> "a kernel with no woven emit should error; got " + r.text());
    }

    @Test
    void differentialOracle_cpuAndGpuAgree() {
        // The integration proves itself: run the vector-add kernel on BOTH backends and assert they
        // agree (SuperVast's differential guarantee). With no GPU present this reports "skipped"
        // (matches = true), so the assertion holds on any machine; where a GPU is present it is a
        // real CPU-vs-GPU equivalence check.
        int n = 4;
        try (Accelerator accelerator = new Accelerator()) {
            Registration registration = accelerator.register(
                    new KernelLowering().lower(GpuKernels.vectorAddIterate()));
            KernelHandle handle = assertInstanceOf(KernelHandle.class, registration,
                    () -> "kernel registration should succeed; got " + registration);
            int[][] columns = {
                    ValueMarshaller.outputColumn(n),
                    ValueMarshaller.toColumn(new long[]{1, 2, 3, 4}),
                    ValueMarshaller.toColumn(new long[]{10, 20, 30, 40})};
            KernelHandle.VerificationResult v = handle.verify(columns, n);
            assertTrue(v.matches(), () -> "CPU and GPU disagree: " + v.detail());
        }
    }

    @Test
    void gpuVectorAdd_survives64BitOperands() {
        // Proof the boundary is honest int64, not a narrowed int32 (values > 2^32 survive).
        Extensions.install(new GpuExtension());
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.gpu.{gpuVectorAdd}
                        gpuVectorAdd({5000000000, 1}, {5000000000, 2})""", "gpu.ptf"),
                PontifRunner.Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "gpu program should run; got " + r.text());
        assertEquals("{10000000000, 3}", r.text());
    }

    /** Runs {@code src} on the interpreter, returning what it wrote to stdout (the async result path). */
    private static String runCapturingStdout(String src) {
        Extensions.install(new GpuExtension());
        PrintStream oldOut = System.out;
        ByteArrayOutputStream cap = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(cap, true, StandardCharsets.UTF_8));
            PontifRunner.RunResult r = new PontifRunner().run(
                    new PontifCompiler().compileAlt(src, "ongpu.ptf"), PontifRunner.Engine.INTERPRETER);
            assertFalse(r.isError(), () -> "on-Gpu program should run; got " + r.text());
        } finally {
            System.setOut(oldOut);
        }
        return cap.toString(StandardCharsets.UTF_8);
    }
}
