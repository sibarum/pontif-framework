package sibarum.pontif.gpu;

import sibarum.pontif.ir.KernelRunners;
import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.runtime.module.Extension;

import java.util.Map;

/**
 * The {@code pontif.gpu} language extension (docs/gpu-kernels.md) — the surface for running a
 * data-parallel computation as a SuperVast compute kernel on the GPU, plus the native dispatch
 * that backs it ({@link GpuKernels}).
 *
 * <p><b>Opt-in.</b> This module is deliberately outside the parent reactor so Vulkan/SuperVast
 * deps never touch the core build; it is discovered via ServiceLoader ({@code META-INF/services})
 * only when present on the classpath. {@code pontif.gpu} lights up when the module is loaded and
 * is an honest link error when it is not.
 *
 * <p><b>Slice 1b — wiring spike:</b> one concrete kernel, {@code gpuVectorAdd}. Slice 1c adds the
 * general {@code … on Gpu} directive.
 */
public final class GpuExtension implements Extension {

    /**
     * Installing the extension injects the GPU kernel runner into the core {@link KernelRunners}
     * seam, so a {@code … on Gpu} iteration has a runner to dispatch to. Absent this module (the
     * opt-in case), the seam stays empty and {@code … on Gpu} is an honest "GPU not loaded" error.
     */
    public GpuExtension() {
        KernelRunners.register(new GpuKernelRunner());
    }

    @Override
    public String moduleName() {
        return "pontif.gpu";
    }

    @Override
    public String pontifSource() {
        return """
                requires pontif.core.{Stream}
                exports @.{gpuVectorAdd}

                # Runs element-wise a + b as a SuperVast compute kernel on the GPU (docs/gpu-kernels.md).
                # A concrete wiring spike; the general `… on Gpu` surface over arbitrary fragments is next.
                function gpuVectorAdd(a:Stream[Int], b:Stream[Int]):Stream[Int] -> {}

                0
                """;
    }

    @Override
    public Map<String, NativeCalls.NativeCall> calls() {
        return Map.of("gpuVectorAdd", GpuKernels::vectorAdd);
    }
}
