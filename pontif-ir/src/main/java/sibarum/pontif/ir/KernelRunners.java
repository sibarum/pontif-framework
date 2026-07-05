package sibarum.pontif.ir;

import java.util.List;

/**
 * The GPU-kernel execution seam (docs/gpu-kernels.md). A {@code gpu}-marked {@link IrExpr.Iterate}
 * (the {@code … on Gpu} directive) is run by a {@link KernelRunner} — the interpreter looks one up
 * here and, if present, hands it the iteration IR plus the evaluated source stream values.
 *
 * <p><b>Core stays GPU-free.</b> This is only the interface + a holder; the implementation is
 * <em>injected</em> by the opt-in {@code pontif-gpu} module (which owns the SuperVast / Vulkan
 * dependencies) when it is on the classpath. With no runner registered, a gpu-marked iteration is
 * an honest runtime error — GPU support simply isn't loaded — never a silent CPU fallback (that
 * would lie about where the work ran). The pattern mirrors {@link NativeCalls}.
 */
public final class KernelRunners {

    /** Runs a {@code gpu}-marked iteration on the GPU, returning its result stream value. */
    @FunctionalInterface
    public interface KernelRunner {
        /**
         * @param iterate      the (gpu-marked) iteration IR — lowered to a kernel by the runner
         * @param sourceValues the evaluated source stream values, in order: the primary source
         *                     then each co-source (the zip inputs)
         * @return the result as a Pontif stream value
         */
        Object run(IrExpr.Iterate iterate, List<Object> sourceValues);
    }

    private static volatile KernelRunner runner;

    private KernelRunners() {}

    /** Installs the GPU kernel runner (called by the {@code pontif-gpu} extension when loaded). */
    public static void register(KernelRunner r) {
        runner = r;
    }

    /** The registered runner, or {@code null} if GPU support is not on the classpath. */
    public static KernelRunner get() {
        return runner;
    }
}
