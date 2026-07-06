package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.RuntimeCheckException;

import java.util.List;
import java.util.function.Supplier;

/**
 * An eager {@code … on Gpu} dispatch (docs/gpu-kernels.md, slice 2). The GPU work is kicked off when
 * the iteration is evaluated ("nothing is lazy — it's always eager", James 2026-07-05); this is the
 * <b>internal</b> handle to that in-flight batch. It backs both consumption legs of the stream/effect
 * duality:
 *
 * <ul>
 *   <li><b>Synchronous (spread).</b> When the produced {@code Stream[Int]} is spread into a consumer
 *       ({@code someFunction(&r)}), the spread is the <em>synchronization point</em>: the interpreter
 *       {@link #markConsumed() marks} this handle and {@link #values() awaits} the batch, then iterates
 *       the per-element results. No {@code await} keyword is involved — a spread over a stream is simply
 *       synchronous, exactly as it is for a CPU stream.</li>
 *   <li><b>Asynchronous (emit/action).</b> If the kernel weaves an {@code emit} and the result is
 *       <em>not</em> spread, the interpreter's drive-to-quiescence loop replays that emit per element
 *       on the host — the same forward-only {@code emit}/{@code action} substrate. A GPU cannot emit, so
 *       the emit is sugar: the GPU computes the value and the effect is deferred.</li>
 * </ul>
 *
 * A program never names or holds a {@code Pending} — it holds a {@code Stream[Int]}; this is the runtime
 * backing. The woven emit is <b>optional</b>: a kernel that only returns a value is observed by spreading
 * its stream. A handle that is <em>neither</em> spread nor carries an emit is never observed — a
 * fail-closed error at drive-to-quiescence.
 *
 * <p>Failure is the {@code !!} hazard: a device {@code Rejection} (or any worker failure) surfaces
 * when {@link #values()} is drained, rethrown as a {@link RuntimeCheckException} (crashes by default;
 * {@code match [!!]} recovery is a later slice).
 */
public final class Pending {

    private final Supplier<List<Object>> valueSupplier;   // deferred: awaits the batch on first call
    private List<Object> memo;
    private boolean drained;
    private final IrExpr eventTemplate;   // null when the kernel weaves no emit (sync-only delivery)
    private final String argVar;          // null iff eventTemplate is null
    private final Origin origin;
    private boolean consumed;             // set once a spread has synchronized on this handle

    /**
     * @param valueSupplier awaits the (eagerly dispatched) batch and returns the per-element computed
     *                      values — invoked lazily on the first {@link #values()} so that all eager
     *                      dispatches are launched before the first synchronization (enabling overlap).
     *                      The single output — also the emit's argument when one is woven.
     * @param eventTemplate the woven completion event's construction with the emitted value replaced by a
     *                      reference to {@code argVar}, or {@code null} when the kernel weaves no emit
     * @param argVar        the placeholder {@code eventTemplate} reads the computed value from, or null
     */
    public Pending(Supplier<List<Object>> valueSupplier, IrExpr eventTemplate, String argVar, Origin origin) {
        this.valueSupplier = valueSupplier;
        this.eventTemplate = eventTemplate;
        this.argVar = argVar;
        this.origin = origin;
    }

    /** Whether this kernel wove an {@code emit} (the asynchronous delivery leg is available). */
    public boolean hasEmit() {
        return eventTemplate != null;
    }

    /** Marks this handle as synchronized-by-a-spread, so drive-to-quiescence won't also replay it. */
    public void markConsumed() {
        consumed = true;
    }

    /** Whether a spread has already synchronized (and delivered) this handle's results. */
    public boolean consumed() {
        return consumed;
    }

    /**
     * Awaits the batch (once, memoized) and returns the per-element computed values in element order.
     * This is the synchronization point — called by a spread that consumes the stream, or by the
     * drive-to-quiescence loop for the async (emit) leg. A device {@code Rejection} (or any worker
     * failure) surfaces as the {@code !!} runtime hazard.
     */
    public List<Object> values() {
        if (drained) {
            return memo;
        }
        try {
            memo = valueSupplier.get();
        } catch (RuntimeCheckException e) {
            throw e;   // already a runtime hazard / check — surface as-is
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new RuntimeCheckException(
                    "`… on Gpu` failed on the device (the !! hazard): " + cause.getMessage(), origin);
        }
        drained = true;
        return memo;
    }

    /** The completion event's construction, with the emit argument replaced by {@link #argVar()}. */
    public IrExpr eventTemplate() {
        return eventTemplate;
    }

    /** The placeholder variable the interpreter binds to each computed value before firing. */
    public String argVar() {
        return argVar;
    }

    public Origin origin() {
        return origin;
    }
}
