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
    void onGpu_boundStream_isConsumedSynchronouslyByASpread() {
        // Slice 2b — the SYNCHRONOUS leg: `… on Gpu` returns a Stream[Int] (dispatched eagerly at the
        // bind), and a spread `log(&r)` synchronizes it — no woven emit needed, delivery IS the spread.
        // The fragment just returns Int; `log` (a plain function that emits StdOut) is spread over it.
        String out = runCapturingStdout("""
                requires pontif.core.{Stream}
                requires pontif.events.{StdOut}
                function log(i:Int):Int -> emit StdOut("" + i + " ")  i
                let a:Stream[Int] = {1, 2, 3, 4}
                let b:Stream[Int] = {10, 20, 30, 40}
                main (
                  let r:Stream[Int] = (&a, &b):[ (x:Int, y:Int) -> x + y ] on Gpu
                  log(&r)
                )""");
        assertEquals("11 22 33 44 ", out);
    }

    @Test
    void onGpu_twoEagerStreams_eachSynchronizedByItsSpread() {
        // Eager dispatch means concurrency needs no new syntax: bind two kernels (both dispatched), then
        // spread each to synchronize it. Sequential spreads ⇒ deterministic order: sum then product.
        String out = runCapturingStdout("""
                requires pontif.core.{Stream}
                requires pontif.events.{StdOut}
                function log(i:Int):Int -> emit StdOut("" + i + " ")  i
                let a:Stream[Int] = {1, 2, 3, 4}
                let b:Stream[Int] = {10, 20, 30, 40}
                let c:Stream[Int] = {2, 3, 4, 5}
                main (
                  let sum:Stream[Int]  = (&a, &b):[ (x:Int, y:Int) -> x + y ] on Gpu
                  let prod:Stream[Int] = (&a, &c):[ (x:Int, y:Int) -> x * y ] on Gpu
                  let s1 = log(&sum)
                  log(&prod)
                )""");
        assertEquals("11 22 33 44 2 6 12 20 ", out);
    }

    @Test
    void onGpu_twoEagerStreamsOfTheSameKernel_bothConcurrentAndCorrect() {
        // Both dispatches share the SAME fragment structure (x+y) → the same cached pipeline. Before
        // per-submission descriptor sets (2c, upstream) a second concurrent dispatch of one pipeline
        // collided; now each has its own set, so both are eagerly in flight and synchronize correctly.
        String out = runCapturingStdout("""
                requires pontif.core.{Stream}
                requires pontif.events.{StdOut}
                function log(i:Int):Int -> emit StdOut("" + i + " ")  i
                let a:Stream[Int] = {1, 2, 3, 4}
                let b:Stream[Int] = {10, 20, 30, 40}
                let c:Stream[Int] = {100, 200, 300, 400}
                main (
                  let r1:Stream[Int] = (&a, &b):[ (x:Int, y:Int) -> x + y ] on Gpu
                  let r2:Stream[Int] = (&a, &c):[ (x:Int, y:Int) -> x + y ] on Gpu
                  let s1 = log(&r1)
                  log(&r2)
                )""");
        assertEquals("11 22 33 44 101 202 303 404 ", out);
    }

    @Test
    void onGpu_decimalKernel_computesInFloat() {
        // Slice 5a — the float foundation: a Decimal kernel lowers to f32 columns (the ruled lossy
        // Decimal→f32 cast). Vector-add over Decimals; `log` (spread) prints each result.
        String out = runCapturingStdout("""
                requires pontif.core.{Stream}
                requires pontif.events.{StdOut}
                function log(d:Decimal):Decimal -> emit StdOut("" + d + " ")  d
                let a:Stream[Decimal] = {1.0, 2.0, 3.0, 4.0}
                let b:Stream[Decimal] = {10.0, 20.0, 30.0, 40.0}
                main (
                  let r:Stream[Decimal] = (&a, &b):[ (x:Decimal, y:Decimal) -> x + y ] on Gpu
                  log(&r)
                )""");
        assertEquals("11.0 22.0 33.0 44.0 ", out);
    }

    @Test
    void onGpu_multiOutput_tupleReturnBecomesAStreamOfTuples() {
        // Multi-output: a tuple return `{x+y, x*y}` lowers to one output column PER member (struct-of-
        // arrays), reassembled into a Stream[{Int,Int}]. A trailing spread consumes each tuple and sums
        // its fields (`t._0 + t._1`), which verifies BOTH output columns: {11+10, 22+40, 33+90, 44+160}.
        Extensions.install(new GpuExtension());
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.core.{Stream}
                        let a:Stream[Int] = {1, 2, 3, 4}
                        let b:Stream[Int] = {10, 20, 30, 40}
                        &( (&a, &b):[ (x:Int, y:Int) -> {x + y, x * y} ] on Gpu ):[ (t:{Int, Int}) -> t._0 + t._1 ]""",
                        "gpu.ptf"),
                PontifRunner.Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "multi-output gpu program should run; got " + r.text());
        assertEquals("{21, 62, 123, 204}", r.text());
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
    void onGpu_repeatedDispatch_lowersTheKernelOnlyOnce() {
        // The spec cache (docs/gpu-kernels.md): registration was already cached, but lowering (SPIR-V
        // generation) used to re-run every dispatch. Now the lowered KernelSpec is cached by the same
        // structural key, so re-running the same fragment lowers zero extra times. Uses arithmetic no
        // other test shares (x*5 + y) so its cache key is fresh regardless of test order; the assertion
        // is on the delta, so a prior population can't mask a regression.
        String src = """
                requires pontif.core.{Stream}
                requires pontif.events.{StdOut, Event}
                struct ScaledSumEvent(r:Int)
                assign trait ScaledSumEvent:Event{}
                let a:Stream[Int] = {1, 2, 3, 4}
                let b:Stream[Int] = {10, 20, 30, 40}
                function scaledSumOnGpu(x:Int, y:Int):Int ->
                  let r = x * 5 + y
                  emit ScaledSumEvent(r)
                  r
                action log(e:ScaledSumEvent) ->
                  emit StdOut("" + e.r + " ")
                  e
                main ( (&a, &b):[ (x:Int, y:Int) -> scaledSumOnGpu(x, y) ] on Gpu )""";
        int before = GpuKernelRunner.loweringCount();
        assertEquals("15 30 45 60 ", runCapturingStdout(src));
        assertEquals("15 30 45 60 ", runCapturingStdout(src));
        assertEquals(1, GpuKernelRunner.loweringCount() - before,
                "the kernel should lower exactly once, then hit the spec cache on the second dispatch");
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
