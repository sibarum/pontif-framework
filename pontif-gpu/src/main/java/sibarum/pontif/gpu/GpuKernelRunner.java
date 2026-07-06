package sibarum.pontif.gpu;

import dev.supirvast.vastir.tools.Accelerator;
import dev.supirvast.vastir.tools.KernelHandle;
import dev.supirvast.vastir.tools.KernelSpec;
import dev.supirvast.vastir.tools.Registration;
import dev.supirvast.vastir.tools.Rejection;
import dev.supirvast.vastir.tools.Submission;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.KernelRunners;
import sibarum.pontif.ir.Pending;
import sibarum.pontif.supirvast.KernelLowering;
import sibarum.pontif.supirvast.ValueMarshaller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The general GPU kernel runner behind the {@code … on Gpu} directive (docs/gpu-kernels.md, slice 2).
 * Registered into the core {@link KernelRunners} seam by {@link GpuExtension} when the opt-in
 * {@code pontif-gpu} module is loaded.
 *
 * <p><b>Delivery is a woven, deferred {@code emit}.</b> The kernel's per-element body emits a
 * user-defined event — the ordinary {@code emit}/{@code action} substrate, nothing GPU-specific.
 * A GPU can't fire events, so the emit is <b>sugar</b>: the runner splits the body into the pure
 * value the emit carries (which the GPU computes, one per element) and the event construction (which
 * the interpreter fires per element on completion, forward-only — no {@code await}). Concretely the
 * runner:
 * <ol>
 *   <li>inlines the fragment lambda and the user function it calls (the {@code emit} lives inside),</li>
 *   <li>extracts the single woven {@code emit}: its argument becomes the kernel's output, and its
 *       event construction (argument replaced by a placeholder) becomes the {@link Pending}'s
 *       completion template,</li>
 *   <li>lowers + marshals synchronously (so a shape-ineligible kernel errors immediately), then
 *       dispatches only the device round trip on a worker thread.</li>
 * </ol>
 * On resolve the interpreter binds each computed value to the placeholder, evaluates the template
 * (so it routes exactly like an author-written {@code emit}), and fires it.
 *
 * <p>v1 supports exactly one woven {@code emit} of a single-field event; multi-field / multi-emit
 * (needing multi-output kernels) fail closed with a source-located error.
 */
public final class GpuKernelRunner implements KernelRunners.KernelRunner {

    /** Placeholder the completion template reads the GPU-computed value from (bound per element). */
    private static final String ARG_VAR = "$gpu0";

    /**
     * A <b>single</b> off-main-thread worker for all GPU dispatch. One thread — not a pool — because
     * the whole point is to build the Vulkan context (instance/device/queue) and each kernel's
     * pipeline <em>once</em> and reuse them ({@link Accelerator} is explicitly designed for this:
     * "repeated runs re-marshal only data"). Creating a fresh {@code Accelerator} per dispatch cost
     * ~580 ms (context create + SPIR-V lowering + {@code spirv-val} + pipeline build + teardown) vs.
     * ~2 ms for a cached re-run — measured. A single worker owns the context (thread-affinity, like
     * the GL root thread; SuperVast's queue isn't free-threaded), and the GPU serializes dispatch
     * anyway. Daemon so it never blocks JVM exit; the context is held for the JVM's life (not closed —
     * teardown is ~90 ms and process exit reclaims it).
     */
    private static final ExecutorService GPU_WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pontif-gpu-worker");
        t.setDaemon(true);
        return t;
    });

    /** The long-lived accelerator, and per-kernel handle cache — touched ONLY on the GPU worker thread. */
    private static Accelerator accelerator;
    private static final Map<String, KernelHandle> HANDLE_CACHE = new HashMap<>();

    /**
     * Lowered-spec cache, keyed by the same structural key as {@link #HANDLE_CACHE}. Registration
     * (the ~580 ms pipeline build) was already cached on the worker thread; this caches the SPIR-V
     * <em>lowering</em> too, so a repeated {@code on Gpu} pays neither. Touched on the calling
     * (interpreter) thread — concurrent because the editor can hold several live interpreters — hence
     * a concurrent map; {@code computeIfAbsent} keeps lowering synchronous there, so a shape-ineligible
     * kernel is still an immediate {@code LoweringError}, never a deferred {@code !!} hazard.
     */
    private static final Map<String, KernelSpec> SPEC_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Test seam: how many kernels have actually been lowered (a cache hit does not increment). */
    private static final java.util.concurrent.atomic.AtomicInteger LOWERINGS =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Count of distinct kernels lowered so far (spec-cache misses) — for the repeated-dispatch test. */
    static int loweringCount() {
        return LOWERINGS.get();
    }

    @Override
    public Object run(IrExpr.Iterate iterate, List<Object> sourceValues,
            KernelRunners.FunctionResolver functions) {
        // Inline the fragment lambda (parser leaves it APPLIED) then the user function it calls, so
        // the per-element body is self-contained (ExprLowering can't lower a Call). The woven `emit`
        // then sits in the body ready to extract.
        IrExpr.Iterate inlined = mapWrite(betaReduce(iterate), v -> inlineCalls(v, functions));

        IrExpr body = soleWriteValue(inlined);
        // The GPU computes the fragment's RETURN value (one per element) — the stream a spread consumes.
        // A woven `emit` is OPTIONAL (the async delivery leg): when present it is stripped to the pure
        // return for the kernel, and its event construction becomes the completion template. v1 is
        // single-output, so `extractEmit` also checks the emitted value IS the returned value (a divergent
        // emit argument is a second output — deferred multi-output kernel). No emit ⇒ the body is already
        // the pure return; delivery is by spreading the produced stream (drive-to-quiescence errors only
        // if the result is neither spread nor emitted).
        Extracted emit = extractEmit(body);
        IrExpr.Iterate kernel = (emit == null) ? inlined : mapWrite(inlined, v -> emit.kernelWrite());

        // Lower now (a shape-ineligible kernel is an immediate LoweringError); the cache key is the pure
        // kernel's structure, so a repeated `on Gpu` reuses its registered pipeline. Only the device round
        // trip runs on the worker.
        String cacheKey = kernel.toString();
        KernelSpec spec = SPEC_CACHE.computeIfAbsent(cacheKey, k -> {
            LOWERINGS.incrementAndGet();
            return new KernelLowering().lower(kernel);
        });

        long[][] inputs = new long[sourceValues.size()][];
        int n = Integer.MAX_VALUE;
        for (int i = 0; i < sourceValues.size(); i++) {
            inputs[i] = GpuKernels.longs(sourceValues.get(i), "source " + i);
            n = Math.min(n, inputs[i].length);            // zip stops at the shortest
        }
        if (n == Integer.MAX_VALUE) n = 0;
        final int count = n;

        // EAGER dispatch (docs/gpu-kernels.md, "eager dispatch, synchronize on spread"): kick the work
        // off now, at the bind. On the GPU this is a non-blocking `submitAsync` (record + submit, no
        // wait) so the worker frees immediately and the NEXT `on Gpu` bind can submit too — two eager
        // binds run concurrently on separate queues. Without a device it falls back to the synchronous
        // CPU path (no overlap to have). This submit task returns a Submission (GPU) or the results (CPU).
        Future<Object> submitted = GPU_WORKER.submit(() -> {
            KernelHandle handle = registerCached(cacheKey, spec);
            int[][] columns = new int[inputs.length + 1][];
            columns[0] = ValueMarshaller.outputColumn(count);         // slot 0 = output
            for (int i = 0; i < inputs.length; i++) {
                columns[i + 1] = ValueMarshaller.toColumn(GpuKernels.prefix(inputs[i], count));
            }
            return handle.preferredBackend() == KernelHandle.Backend.GPU
                    ? handle.submitAsync(columns, count)              // in flight — awaited at the spread
                    : handle.run(columns, count);                     // CPU fallback — synchronous
        });

        // The synchronization point (a spread, or the async drive): await on the worker, then box.
        // Deferred so all eager submits are launched before the first await — that is what overlaps them.
        java.util.function.Supplier<List<Object>> await = () -> {
            try {
                return GPU_WORKER.submit(() -> {
                    Object s = submitted.get();
                    int[][] result = (s instanceof Submission sub)
                            ? registerCached(cacheKey, spec).await(sub)
                            : (int[][]) s;
                    long[] out = ValueMarshaller.fromColumn(result[0], count);
                    List<Object> boxed = new ArrayList<>(out.length);
                    for (long v : out) boxed.add(v);                  // Pontif Int = Long at runtime
                    return boxed;
                }).get();
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new RuntimeException(cause.getMessage(), cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("`… on Gpu` was interrupted while pending", e);
            }
        };
        return new Pending(await, emit == null ? null : emit.eventTemplate(),
                emit == null ? null : ARG_VAR, iterate.origin());
    }

    /**
     * Returns the registered handle for {@code spec}, building it once and caching it (keyed by the
     * kernel's structure). Runs only on the GPU worker thread, so the shared {@link #accelerator} and
     * {@link #HANDLE_CACHE} need no synchronization. A device/validation {@link Rejection} throws —
     * the interpreter surfaces it as the {@code !!} hazard when it drains the {@link Pending}.
     */
    private static KernelHandle registerCached(String cacheKey, KernelSpec spec) {
        KernelHandle cached = HANDLE_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        if (accelerator == null) {
            accelerator = new Accelerator();          // one-time Vulkan context (~1.9 s cold), then reused
        }
        Registration registration = accelerator.register(spec);
        if (!(registration instanceof KernelHandle handle)) {
            Rejection rejection = (Rejection) registration;
            throw new RuntimeException("`… on Gpu`: the GPU rejected the kernel — "
                    + rejection.reason() + ": " + rejection.detail());
        }
        HANDLE_CACHE.put(cacheKey, handle);
        return handle;
    }

    /** A split woven emit: the pure value the GPU computes, and the event to fire per element. */
    private record Extracted(IrExpr kernelWrite, IrExpr eventTemplate) {}

    /**
     * Splits the woven {@code emit} out of a kernel body. Walks the enclosing {@code let}s to the
     * (single) {@code emit}, replaces it with the value its event carries (→ the kernel output), and
     * rebuilds the event construction with that value replaced by {@link #ARG_VAR} (→ the completion
     * template). Returns {@code null} if there is no {@code emit}; fails closed on shapes v1 can't
     * lower (multi-field events need multi-output kernels).
     */
    private static Extracted extractEmit(IrExpr e) {
        return switch (e) {
            case IrExpr.Emit em -> {
                if (!(em.event() instanceof IrExpr.Record rec) || rec.typeName() == null) {
                    throw new RuntimeCheckException(
                            "`… on Gpu`: the woven `emit` must construct a named event struct", em.origin());
                }
                if (rec.members().size() != 1) {
                    throw new RuntimeCheckException(
                            "`… on Gpu`: v1 supports a single-field event (the GPU computes one value "
                                    + "per element); '" + rec.typeName() + "' has " + rec.members().size()
                                    + " fields — multi-field events (multi-output kernels) are a later slice.",
                            em.origin());
                }
                Map.Entry<String, IrExpr> field = rec.members().entrySet().iterator().next();
                // v1 is single-output: the GPU computes ONE value per element, delivered both as the
                // stream element (a spread) and as the emit's argument (an action). The emitted value is
                // that single output; the emit's continuation (the fragment's textual return) is not a
                // second output in v1 — a genuinely divergent return is the deferred multi-output kernel.
                IrExpr template = new IrExpr.Record(rec.typeName(),
                        Map.of(field.getKey(), new IrExpr.Var(ARG_VAR, em.origin())),
                        rec.runtimeChecks(), rec.origin());
                yield new Extracted(field.getValue(), template);   // kernel output = the emitted value
            }
            case IrExpr.LetIn let -> {
                Extracted inner = extractEmit(let.body());
                yield inner == null ? null : new Extracted(
                        new IrExpr.LetIn(let.name(), let.declaredSort(), let.value(),
                                inner.kernelWrite(), let.origin()),
                        inner.eventTemplate());
            }
            default -> null;
        };
    }

    /**
     * Inlines the applied fragment in each arm's write value ({@code Apply(λ, args)} → the lambda
     * body with its params substituted by the args), so the kernel body is the arithmetic
     * {@code KernelLowering}/{@code ExprLowering} lower directly.
     */
    private static IrExpr.Iterate betaReduce(IrExpr.Iterate it) {
        return mapWrite(it, GpuKernelRunner::inlineApplied);
    }

    /** Rebuilds {@code it} with {@code f} applied to every arm write value. */
    private static IrExpr.Iterate mapWrite(IrExpr.Iterate it, java.util.function.UnaryOperator<IrExpr> f) {
        List<IrExpr.Arm> arms = new ArrayList<>(it.arms().size());
        for (IrExpr.Arm arm : it.arms()) {
            List<IrExpr.Write> writes = new ArrayList<>(arm.writes().size());
            for (IrExpr.Write w : arm.writes()) {
                writes.add(new IrExpr.Write(w.output(), w.key(), f.apply(w.value())));
            }
            arms.add(new IrExpr.Arm(arm.pattern(), writes));
        }
        return new IrExpr.Iterate(it.source(), it.coSources(), it.element(),
                it.outputs(), arms, it.origin(), it.gpu());
    }

    /** The single arm's single write value (map/zip shape); the fuller shape check is KernelLowering's. */
    private static IrExpr soleWriteValue(IrExpr.Iterate it) {
        return it.arms().get(0).writes().get(0).value();
    }

    private static IrExpr inlineApplied(IrExpr e) {
        if (e instanceof IrExpr.Apply ap && ap.fn() instanceof IrExpr.Lambda lam
                && lam.params().size() == ap.args().size()) {
            Map<String, IrExpr> env = new HashMap<>();
            for (int i = 0; i < lam.params().size(); i++) {
                env.put(lam.params().get(i).name(), ap.args().get(i));
            }
            return subst(lam.body(), env);
        }
        return e;
    }

    /**
     * Inlines user-function calls: {@code Call(f, args)} → {@code f}'s body with its params bound to
     * {@code args} (recursively, so a chain of calls flattens). A call the resolver doesn't recognise
     * (a native/builtin) is left as-is — {@code KernelLowering} then accepts or rejects it.
     */
    private static IrExpr inlineCalls(IrExpr e, KernelRunners.FunctionResolver functions) {
        return switch (e) {
            case IrExpr.Call c -> {
                List<IrExpr> args = c.args().stream().map(a -> inlineCalls(a, functions)).toList();
                KernelRunners.ResolvedFunction fn = functions.resolve(c.functionName(), args.size());
                if (fn == null) {
                    yield new IrExpr.Call(c.functionName(), args, c.origin());
                }
                Map<String, IrExpr> env = new HashMap<>();
                for (int i = 0; i < fn.paramNames().size(); i++) env.put(fn.paramNames().get(i), args.get(i));
                yield inlineCalls(subst(fn.body(), env), functions);
            }
            case IrExpr.BinOp op -> new IrExpr.BinOp(op.op(),
                    inlineCalls(op.left(), functions), inlineCalls(op.right(), functions), op.origin());
            case IrExpr.LetIn let -> new IrExpr.LetIn(let.name(), let.declaredSort(),
                    inlineCalls(let.value(), functions), inlineCalls(let.body(), functions), let.origin());
            case IrExpr.FieldAccess fa ->
                    new IrExpr.FieldAccess(inlineCalls(fa.base(), functions), fa.fieldName(), fa.origin());
            case IrExpr.Emit em -> new IrExpr.Emit(
                    inlineCalls(em.event(), functions), inlineCalls(em.body(), functions), em.origin());
            case IrExpr.Record rec -> {
                Map<String, IrExpr> members = new LinkedHashMap<>();
                rec.members().forEach((k, v) -> members.put(k, inlineCalls(v, functions)));
                yield new IrExpr.Record(rec.typeName(), members, rec.runtimeChecks(), rec.origin());
            }
            case IrExpr.Apply ap -> new IrExpr.Apply(inlineCalls(ap.fn(), functions),
                    ap.args().stream().map(a -> inlineCalls(a, functions)).toList(), ap.origin());
            default -> e;
        };
    }

    /** Capture-free enough for fragment + function bodies (arithmetic, field access, let, emit, record). */
    private static IrExpr subst(IrExpr e, Map<String, IrExpr> env) {
        return switch (e) {
            case IrExpr.Var v -> env.getOrDefault(v.name(), v);
            case IrExpr.BinOp op -> new IrExpr.BinOp(op.op(), subst(op.left(), env), subst(op.right(), env), op.origin());
            case IrExpr.FieldAccess fa -> new IrExpr.FieldAccess(subst(fa.base(), env), fa.fieldName(), fa.origin());
            case IrExpr.Call c -> new IrExpr.Call(c.functionName(),
                    c.args().stream().map(a -> subst(a, env)).toList(), c.origin());
            case IrExpr.LetIn let -> new IrExpr.LetIn(let.name(), let.declaredSort(),
                    subst(let.value(), env), subst(let.body(), env), let.origin());
            case IrExpr.Emit em -> new IrExpr.Emit(subst(em.event(), env), subst(em.body(), env), em.origin());
            case IrExpr.Record rec -> {
                Map<String, IrExpr> members = new LinkedHashMap<>();
                rec.members().forEach((k, v) -> members.put(k, subst(v, env)));
                yield new IrExpr.Record(rec.typeName(), members, rec.runtimeChecks(), rec.origin());
            }
            case IrExpr.Apply ap -> new IrExpr.Apply(subst(ap.fn(), env),
                    ap.args().stream().map(a -> subst(a, env)).toList(), ap.origin());
            default -> e;   // Lit/Dec/Bool/etc. carry no vars; unsupported nodes fail closed in lowering
        };
    }
}
