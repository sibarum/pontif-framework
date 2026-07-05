package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.RuntimeCheckException;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * A GPU dispatch in flight — the async, <b>internal</b> handle for a {@code … on Gpu} iteration
 * (docs/gpu-kernels.md, slice 2). Not user-facing: a program never names or holds a {@code Pending}
 * (there is no {@code await} — RULED, James 2026-07-05); it exists so the interpreter's
 * drive-to-quiescence loop knows there is outstanding work to wait for and deliver.
 *
 * <p><b>Delivery is a woven, deferred {@code emit}.</b> The kernel's per-element body emits a
 * user-defined event (the same {@code emit}/{@code action} machinery as the rest of the event
 * substrate — nothing GPU-specific). That emit is <b>sugar, not a live per-element fire</b> (a GPU
 * cannot emit): the GPU computes the emit's <em>argument</em> (a pure value, one per element), and
 * once the batch resolves the interpreter fires the emit on the main thread — for each computed
 * value, it evaluates {@link #eventTemplate()} with {@link #argVar()} bound to that value and routes
 * the resulting event through {@code fireEvent}. So the GPU does the computation; only the effect is
 * deferred to the host, in element order, single-threaded.
 *
 * <p>Failure is the {@code !!} hazard: a device {@code Rejection} (or any worker failure) surfaces
 * when {@link #values()} is drained, rethrown as a {@link RuntimeCheckException} (crashes by default;
 * {@code match [!!]} recovery is a later slice).
 */
public final class Pending {

    private final Future<List<Object>> values;
    private final IrExpr eventTemplate;
    private final String argVar;
    private final Origin origin;

    /**
     * @param values        the per-element GPU-computed emit arguments (resolves when the batch runs)
     * @param eventTemplate the completion event's construction, with the emit argument replaced by a
     *                      reference to {@code argVar} (so the interpreter re-binds it per element)
     * @param argVar        the placeholder variable {@code eventTemplate} reads the computed value from
     */
    public Pending(Future<List<Object>> values, IrExpr eventTemplate, String argVar, Origin origin) {
        this.values = values;
        this.eventTemplate = eventTemplate;
        this.argVar = argVar;
        this.origin = origin;
    }

    /**
     * Blocks until the GPU batch resolves, returning the per-element computed emit arguments (in
     * element order). A worker failure (a device {@code Rejection}) is rethrown as the {@code !!}
     * runtime hazard. Called by the interpreter's drive-to-quiescence loop on the main thread.
     */
    public List<Object> values() {
        try {
            return values.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new RuntimeCheckException(
                    "`… on Gpu` failed on the device (the !! hazard): " + cause.getMessage(), origin);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeCheckException("`… on Gpu` was interrupted while pending", origin);
        }
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
