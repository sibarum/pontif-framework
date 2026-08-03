package sibarum.pontif.runtime.module;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A bounded, thread-safe <b>inbox</b> — the one and only object two Players share (docs/orchestration.md,
 * §"The concurrency model"). Everything that flows through it is an immutable message, so there is nothing to
 * lock <em>inside</em> a message; all the concurrency in the whole model is confined to this queue.
 *
 * <p>A Player is a single-owner actor: exactly one thread drains its Mailbox and folds its state serially, so
 * the state needs no lock either. The bound gives natural <b>backpressure</b> — a full inbox blocks the sender
 * ({@link #send}) until the owner catches up, which is what stops a fast producer from swamping a slow consumer.
 *
 * <p><b>Decoupled ends.</b> Backed by a {@link LinkedBlockingQueue} — the two-lock queue, with <em>separate</em>
 * put and take locks — so producers and the consumer never contend on the same lock: heavy traffic on the send
 * side does not stall the drain side. A mailbox is many-producers / single-consumer (Players emit in, its owner
 * drains), so the drain side sees no contention at all. (The JDK has no lock-free queue that is also bounded and
 * blocking — that would need an external SPSC ring — so the two-lock queue is the right backpressured pick.)
 *
 * <p><b>Transport-agnostic.</b> The Mailbox knows nothing of tiers: it is just a concurrent queue of immutable
 * messages. This is the same-process-thread row (the message is handed over by reference — the heap is shared,
 * and the message is immutable so that is safe). Higher tiers reuse the exact same boundary with a different
 * transport underneath — a socket-fed inbox for a process, whose frames are this message serialized. Nothing
 * above the Mailbox can tell which tier it is on.
 */
public final class Mailbox<M> {

    private final BlockingQueue<M> queue;

    /** A Mailbox holding at most {@code capacity} in-flight messages before {@link #send} applies backpressure. */
    public Mailbox(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("mailbox capacity must be >= 1, was " + capacity);
        }
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    /** Deliver {@code message} to the owner, blocking while the inbox is full (backpressure). */
    public void send(M message) {
        try {
            queue.put(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while sending to a mailbox", e);
        }
    }

    /** Take the next message, blocking until one arrives. Only the owning thread calls this. */
    public M take() {
        try {
            return queue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while receiving from a mailbox", e);
        }
    }

    /** The next message if one is already waiting, else {@code null} — a non-blocking drain for a cooperative lane. */
    public M poll() {
        return queue.poll();
    }
}
