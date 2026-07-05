package sibarum.pontif.gpu;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;
import sibarum.pontif.runtime.module.Extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
    void onGpu_directive_runsZipOnTheGpu() {
        // The general surface: an ordinary zip iteration, marked `on Gpu`, runs as a kernel.
        Extensions.install(new GpuExtension());
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.core.{Stream}
                        let a:Stream[Int] = {1, 2, 3, 4}
                        let b:Stream[Int] = {10, 20, 30, 40}
                        (&a, &b):[ (x:Int, y:Int) -> x + y ] on Gpu""", "ongpu.ptf"),
                PontifRunner.Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "on-Gpu program should run; got " + r.text());
        assertEquals("{11, 22, 33, 44}", r.text());
    }

    @Test
    void onGpu_directive_runsMapOnTheGpu() {
        // Single-source map: squares, on the GPU.
        Extensions.install(new GpuExtension());
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.core.{Stream}
                        let s:Stream[Int] = {1, 2, 3, 4}
                        &s:[ (x:Int) -> x * x ] on Gpu""", "ongpu.ptf"),
                PontifRunner.Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "on-Gpu map should run; got " + r.text());
        assertEquals("{1, 4, 9, 16}", r.text());
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
}
