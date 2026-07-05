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

    /** Runs a {@code gpu}-marked iteration on the GPU (docs/gpu-kernels.md, slice 2). */
    @FunctionalInterface
    public interface KernelRunner {
        /**
         * Dispatches the kernel on a worker thread and returns a {@link Pending} immediately (async).
         * The kernel's per-element body carries a woven {@code emit} of a user event (the delivery
         * mechanism — forward-only, no {@code await}); the GPU computes the emit's <em>argument</em>,
         * and the returned {@link Pending} carries the completion-event template so the interpreter
         * can fire it per element once the batch resolves.
         *
         * @param iterate      the (gpu-marked) iteration IR — lowered to a kernel by the runner
         * @param sourceValues the evaluated source stream values, in order: the primary source
         *                     then each co-source (the zip inputs)
         * @param functions    resolves a user-function call in the kernel body to its (params, body)
         *                     so the runner can inline it (the {@code emit} lives inside such a
         *                     function); returns {@code null} for non-user calls
         * @return a {@link Pending} tracking the async dispatch and its deferred completion emits
         */
        Object run(IrExpr.Iterate iterate, List<Object> sourceValues, FunctionResolver functions);
    }

    /** Resolves a user-function call in a kernel body to its parameters and body, for inlining. */
    @FunctionalInterface
    public interface FunctionResolver {
        /** The function matching {@code name}/{@code arity}, or {@code null} if it is not a user function. */
        ResolvedFunction resolve(String name, int arity);
    }

    /** A user function's parameter names and body — enough to inline a call by substitution. */
    public record ResolvedFunction(List<String> paramNames, IrExpr body) {}

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
