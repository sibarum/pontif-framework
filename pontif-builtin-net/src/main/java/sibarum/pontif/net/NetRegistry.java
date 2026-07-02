package sibarum.pontif.net;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A process-wide table mapping a small integer id to a live {@link NetConduitHandle}.
 *
 * <p>Pontif's dispatch is symbolic: every value passed to a function is converted to a
 * {@code SymExpr}, which only the language's own scalars and records support &mdash; an opaque
 * native object cannot cross that boundary. So a {@code pontif.net} conduit is surfaced to programs
 * as an ordinary struct {@code NetConduit(id:Int)} (a plain {@code Int} field, which converts
 * cleanly), and the real handle lives here, looked up by that id. This is the standard trick for
 * giving a language a first-class handle to a native resource without leaking the resource itself
 * into the value world.
 */
public final class NetRegistry {

    private static final ConcurrentHashMap<Long, NetConduitHandle> HANDLES = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    private NetRegistry() {}

    /** Registers {@code handle} and returns its fresh id. */
    public static long register(NetConduitHandle handle) {
        long id = NEXT_ID.getAndIncrement();
        HANDLES.put(id, handle);
        return id;
    }

    /** The handle for {@code id}, or throws if it is unknown (closed or never opened). */
    public static NetConduitHandle require(long id) {
        NetConduitHandle handle = HANDLES.get(id);
        if (handle == null) {
            throw new IllegalStateException("no pontif.net conduit with id " + id);
        }
        return handle;
    }

    /** Removes and returns the handle for {@code id}, or null if unknown. */
    public static NetConduitHandle remove(long id) {
        return HANDLES.remove(id);
    }
}
