package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;
import sibarum.pontif.core.types.RecordValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The same-process thread tier (docs/orchestration.md, cut 3b): a daemon and an unbounded inbox per
 * conductor, the main thread as a lane drained cooperatively, and a run that ends at quiescence.
 *
 * <p>This is the transport the interpreter carried inline until the lane code was lifted out behind
 * {@link LaneTransport}; its behaviour is unchanged. It remains the default, and what a host without a
 * container (the CLI, the tests) uses.
 */
public final class ThreadLaneTransport implements LaneTransport {

    /** A unit of deferred work on a lane: fire this (immutable) event when the owning thread drains it. */
    private record LaneTask(RecordValue event, Origin origin) {}

    /** Poison pill: enqueued at teardown so a blocked daemon returns from {@link #drainLane}. */
    private static final LaneTask POISON = new LaneTask(null, null);

    /** One lane: an inbox and the thread that drains it. {@code thread} is null for the main lane. */
    private static final class Lane {
        final String name;
        final BlockingQueue<LaneTask> inbox = new LinkedBlockingQueue<>();
        Thread thread;
        Lane(String name) { this.name = name; }
    }

    private volatile Map<String, Lane> lanes = Map.of();
    private volatile Lane mainLane;
    private volatile Thread mainThread;
    private volatile Fire fire;
    /** Events enqueued to any lane but not yet fully processed. Reaches 0 exactly at quiescence. */
    private final AtomicLong inFlight = new AtomicLong();
    /** First uncaught handler failure on any lane; a crash is a full halt (docs/orchestration.md, Failure). */
    private volatile RuntimeException laneFailure;

    @Override
    public void start(Set<String> conductors, Fire fire) {
        this.fire = fire;
        mainThread = Thread.currentThread();
        mainLane = new Lane(MAIN);
        Map<String, Lane> started = new LinkedHashMap<>();
        for (String c : conductors) started.put(c, new Lane(c));
        lanes = started;
        // All lanes listening BEFORE the first message flows — the init race is designed out.
        for (Lane l : started.values()) {
            l.thread = new Thread(() -> drainLane(l), "pontif-conductor-" + l.name);
            l.thread.setDaemon(true);
            l.thread.start();
        }
    }

    private Lane lane(String name) {
        Lane l = MAIN.equals(name) ? mainLane : lanes.get(name);
        if (l == null) throw new IllegalStateException("no lane '" + name + "' is seated");
        return l;
    }

    @Override
    public boolean onLane(String lane) {
        Lane l = lane(lane);
        return Thread.currentThread() == (l.thread != null ? l.thread : mainThread);
    }

    @Override
    public void send(String lane, RecordValue event, Origin origin) {
        inFlight.incrementAndGet();
        lane(lane).inbox.add(new LaneTask(event, origin));   // unbounded — bounded backpressure is a refinement
    }

    /** A daemon's run loop: drain the inbox, fire each event on THIS thread (single-owner), until poisoned. */
    private void drainLane(Lane lane) {
        while (true) {
            LaneTask t;
            try {
                t = lane.inbox.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (t == POISON) return;
            runTask(t);
        }
    }

    private void runTask(LaneTask t) {
        try {
            fire.fire(t.event(), t.origin());
        } catch (RuntimeException ex) {
            if (laneFailure == null) laneFailure = ex;   // first crash wins; a crash halts the program
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /**
     * Cooperatively drain the main lane's inbox until no event is in flight on any lane, then poison and
     * join the daemons. A handler crash on any lane aborts the drive and is rethrown here.
     */
    @Override
    public void drive() {
        try {
            while (laneFailure == null) {
                LaneTask t;
                try {
                    t = mainLane.inbox.poll(1, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (t != null) {
                    runTask(t);
                    continue;
                }
                if (inFlight.get() == 0) break;   // inbox empty AND nothing in flight anywhere ⇒ quiescent
            }
        } finally {
            for (Lane l : lanes.values()) l.inbox.add(POISON);
            for (Lane l : lanes.values()) join(l);
            lanes = Map.of();
        }
        if (laneFailure != null) {
            RuntimeException e = laneFailure;
            laneFailure = null;
            throw e;
        }
    }

    private static void join(Lane lane) {
        try {
            lane.thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted joining lane " + lane.name, e);
        }
    }
}
