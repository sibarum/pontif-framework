package sibarum.pontif.host;

import dev.vexelray.framework.core.WakeSource;
import dev.vexelray.framework.shell.Placement;
import dev.vexelray.framework.shell.Placements;
import sibarum.atchung.Atchung;
import sibarum.atchung.Backpressure;
import sibarum.atchung.Fold;
import sibarum.atchung.Pump;
import sibarum.atchung.Subscription;
import sibarum.atchung.Topic;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.ir.LaneTransport;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A Pontif program's lanes, carried by the framework's threads and the framework's bus
 * (docs/orchestration.md, "one pattern, four transports").
 *
 * <p>A seated conductor is a {@link Placement}: a platform thread of the application's lanes and a mailbox on
 * the bus, so it is named in a thread dump, watched by the watchdog, woken into the loop after a delivery and
 * drained-then-stopped at shutdown without this class doing any of it. The main conductor is a mailbox
 * drained on the main thread in {@code FrameStage.APP} - {@link #mainDrain} is that hook - and a send to it
 * from another thread wakes the loop, because to a loop that parks an event nobody announced does not exist.
 *
 * <p><b>The interpreter's rule is unchanged.</b> An event is handed to its owning lane unless the sending
 * thread already owns it, and then it folds inline. {@link #onLane} reads that off which lane the thread is
 * serving, so a native handler thread (a click, a timer) owns no lane and its events are always sent.
 *
 * <p><b>A crash is a halt.</b> Pontif deviates from Erlang here (docs/orchestration.md, <i>Failure</i>): totality
 * makes a handler crash exceptional, a crash can land mid-effect, and no journal makes replay safe, so a
 * handler that throws ends the program rather than being caught and retired. The framework's own policy is the
 * same one for a mailbox that cannot keep up, so both go through {@code onCrash}, which is the process-wide
 * {@code Fatal} by default. {@code Placement} would otherwise catch the throw and keep draining.
 *
 * <p><b>Loss is the event's declaration.</b> A lane is one mailbox that never drops an edge: an overflow halts,
 * which is the framework's contract for an event nothing can reconstruct. An event whose sort satisfies
 * {@code pontif.events.Sample} is a reading its next value supersedes, so it folds - the mailbox is an atchung
 * {@code Fold} keyed on the event's type, and a queued {@code Moved} is replaced by a newer {@code Moved} without
 * disturbing anything else. One mailbox rather than one per class, because folding moves the survivor to the
 * back of the queue, which keeps a lane's events in arrival order across both classes; folding is lossless
 * here for the reason it is only conditionally so elsewhere: a conductor's state is single-owner, so nothing
 * can observe it between a publish and its drain.
 *
 * <p><b>The capacity is for edges.</b> It is deliberately large so that a burst of {@code emit}s from
 * {@code main} before the lanes start is not mistaken for a component falling behind; it is not yet settable
 * from the program.
 *
 * <p><b>The lifetime is the framework's.</b> {@link #drive} has nothing to do: the frame loop is already
 * serving the main lane, and the program ends when the window closes, not when events stop flowing.
 */
public final class FrameworkLaneTransport implements LaneTransport, WakeSource, AutoCloseable {

    /** Large enough that FAIL means "a lane stopped draining", not "main emitted a lot before the loop began". */
    public static final int DEFAULT_CAPACITY = 65_536;

    /** What crosses a lane: an immutable event and where it came from. Deeply immutable, so it is never copied. */
    public record Message(RecordValue event, Origin origin, Loss loss) {}

    /**
     * Which queued message a new one supersedes: a sample's cell is its event type, so the latest {@code Moved}
     * replaces a queued {@code Moved} and nothing else; an edge names no cell and queues on its own.
     */
    private static final Fold<Message> SAMPLES_BY_TYPE =
            m -> m.loss() == Loss.SAMPLE ? m.event().typeName() : null;

    private final Atchung bus;
    private final Function<String, Placement> place;
    private final Consumer<RuntimeException> onCrash;
    private final int capacity;

    private final Map<String, Topic<Message>> topics = new ConcurrentHashMap<>();
    /** The lane whose delivery this thread is inside, or null on a thread that is serving none. */
    private final ThreadLocal<String> serving = new ThreadLocal<>();

    private volatile Thread mainThread;
    private volatile Runnable wake = () -> { };
    private Pump mainPump;
    private Subscription mainMailbox;

    /**
     * @param bus     the application's bus, so a lane's mailbox is an ordinary subscription a probe can watch
     * @param place   how a conductor becomes a component - {@code shell::place} in an application, a
     *                standalone {@code Placement} in a test
     * @param onCrash what a handler's uncaught failure does; a crash halts, so this must not return normally
     *                in production
     */
    public FrameworkLaneTransport(Atchung bus, Function<String, Placement> place,
                                  Consumer<RuntimeException> onCrash, int capacity) {
        this.bus = bus;
        this.place = place;
        this.onCrash = onCrash;
        this.capacity = capacity;
    }

    /** As above, with a crash that goes through the process-wide fault policy ({@code Fatal}). */
    public FrameworkLaneTransport(Atchung bus, Function<String, Placement> place) {
        this(bus, place, cause -> Atchung.fatal().fault(cause), DEFAULT_CAPACITY);
    }

    private Topic<Message> topic(String lane) {
        return topics.computeIfAbsent(lane, l -> Topic.of("pontif.lane." + l, Message.class));
    }

    @Override
    public void start(Set<String> conductors, Fire fire) {
        mainThread = Thread.currentThread();
        // Every mailbox exists before the first message flows, so the init race is designed out; the
        // placements are STARTED later, by the container, once everything that might publish to them exists.
        for (String conductor : conductors) {
            Placements.mailbox(place.apply(conductor), topic(conductor), m -> deliver(conductor, fire, m),
                    capacity, Backpressure.FAIL, SAMPLES_BY_TYPE);
        }
        mainPump = bus.pump();
        mainMailbox = mainPump.subscribe(topic(MAIN), m -> deliver(MAIN, fire, m),
                capacity, Backpressure.FAIL, SAMPLES_BY_TYPE);
    }

    private void deliver(String lane, Fire fire, Message m) {
        String outer = serving.get();
        serving.set(lane);
        try {
            fire.fire(m.event(), m.origin());
        } catch (RuntimeException e) {
            onCrash.accept(e);
        } finally {
            if (outer == null) serving.remove(); else serving.set(outer);
        }
    }

    @Override
    public boolean onLane(String lane) {
        String current = serving.get();
        if (current != null) return current.equals(lane);
        return MAIN.equals(lane) && Thread.currentThread() == mainThread;
    }

    @Override
    public void send(String lane, RecordValue event, Origin origin, Loss loss) {
        bus.publish(topic(lane), new Message(event, origin, loss));
        if (MAIN.equals(lane)) wake.run();
    }

    /** The {@code FrameStage.APP} hook that serves the main lane on the main thread. */
    public Runnable mainDrain() {
        return () -> {
            if (mainPump != null) mainPump.drain();
        };
    }

    /** The framework connects this to the loop in ATTACH; a send to the main lane then wakes it. */
    @Override
    public void onWake(Runnable onWake) {
        this.wake = onWake == null ? () -> { } : onWake;
    }

    /** The frame loop outlives {@code main}: a click after it returns must still reach the lane that owns it. */
    @Override
    public boolean survivesMain() {
        return true;
    }

    /** Nothing to do: the loop is the framework's and is already serving the main lane. */
    @Override
    public void drive() {
    }

    @Override
    public void close() {
        if (mainMailbox != null) mainMailbox.close();
    }
}
