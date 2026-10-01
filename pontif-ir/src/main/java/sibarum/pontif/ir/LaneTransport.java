package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;
import sibarum.pontif.core.types.RecordValue;

import java.util.Set;

/**
 * How an emitted event reaches the lane that owns it (docs/orchestration.md, "one pattern, four
 * transports"). The interpreter decides <i>which</i> lane owns an event from the static routing table;
 * a transport decides <i>how</i> it gets there and <i>how the program stays alive</i> — the two columns
 * that differ between tiers.
 *
 * <p>Lanes are named by conductor; the main lane is {@link #MAIN}. Events are immutable, so a transport
 * may hand them across threads without copying, and only its own queue is ever shared. The interpreter
 * never touches a thread, a queue or a counter — everything the concurrency model needs a lock for
 * lives behind this interface.
 *
 * <p>One instance serves one run: {@link #start} is called before any event flows and {@link #drive}
 * ends the run.
 */
public interface LaneTransport {

    /** The main lane's name — the thread that called {@code eval}, and the only lane that is not a conductor. */
    String MAIN = "<main>";

    /** Fires an event on the calling thread; supplied by the interpreter, called on the owning lane's thread. */
    @FunctionalInterface
    interface Fire {
        void fire(RecordValue event, Origin origin);
    }

    /**
     * Stand up one lane per conductor (the main lane is implicit) so every lane is listening before the
     * first message flows — the init race is designed out. {@code fire} is how a lane runs an event it
     * drained. Called on the main thread.
     */
    void start(Set<String> conductors, Fire fire);

    /** Whether the calling thread is the one that owns {@code lane}; if so the interpreter folds inline. */
    boolean onLane(String lane);

    /** Hand an immutable event to {@code lane}'s inbox and return; the owner fires it on its own thread. */
    void send(String lane, RecordValue event, Origin origin);

    /**
     * Called on the main thread once {@code main} has returned: serve the main lane until the program is
     * done, then stop the others. A handler crash on any lane is rethrown here — a crash halts the program.
     * What "done" means is the transport's: the thread tier ends at quiescence, a windowed host ends when
     * the window closes.
     */
    void drive();

    /**
     * Whether the run continues after {@link #drive} returns. The thread tier is over when the orchestra
     * drains, so the interpreter tears its lanes down and later events fold inline. A windowed host is not:
     * the loop outlives {@code main}, and a click arriving afterwards must still reach the lane that owns it.
     */
    default boolean survivesMain() {
        return false;
    }
}
