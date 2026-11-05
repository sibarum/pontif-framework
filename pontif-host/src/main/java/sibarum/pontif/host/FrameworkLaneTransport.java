package sibarum.pontif.host;

import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.shell.Inboxes;
import dev.vexelray.framework.shell.Shell;
import sibarum.atchung.Inbox;
import sibarum.atchung.Sender;
import sibarum.atchung.Topic;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.ir.LaneTransport;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A Pontif program's lanes, carried by the framework's logic tick (docs/orchestration.md, "one pattern, four
 * transports"; vexelray-framework's {@code docs/threading.md}, the message model).
 *
 * <p><b>A lane is a component's inbox.</b> The main lane, and every conductor seated with it, is one inbox on the
 * logic lane's own share, so by default a whole program runs on {@code vexel-logic} and on no thread of its own
 * (T1.6: one logic lane by default). A conductor seated {@code over thread} asks for a core, which is what a
 * declared lane is: its inbox is on a lane named after it, delivered by {@code vexel-component-<name>} in the same
 * rounds as everyone else (T5.6). Every inbox exists before the first tick, because the conductor graph is fixed
 * at compile time and this is built from it in the wiring's {@code CONFIG}, where generated wiring makes its
 * components.
 *
 * <p><b>Loss is the topic's kind.</b> Each lane hears two topics: an edge topic, every message in order, whose
 * overflow is a fault (T4.2), and a state topic keyed by the event's type, so a queued {@code Sample} is replaced by
 * a newer one of its type and nothing else is touched (T4.1). One inbox holds both in a single arrival order, so a
 * lane's events still arrive in the order they were sent across both kinds.
 *
 * <p><b>The tick is the only order.</b> Once ticks run, {@link #onLane} answers no for every lane, the lane's own
 * included: an emit is queued for the next round and sent through the sender of the inbox whose delivery made it,
 * so the hop count, the tie-break across lanes and a replay all see it (T4.5, T5.6, T5.7). Only {@code main}
 * itself folds inline, on the thread that runs it, while the wiring builds the tree: no tick runs then (T6.1), so
 * nothing else can observe the difference. A send from outside any delivery (a view hearing a click, a frame
 * hook's beat) is an ordinary publish and waits for the next tick.
 *
 * <p><b>A crash is a halt.</b> A handler that throws is not caught here: the tick rethrows it and the framework's
 * fault policy ends the program (docs/orchestration.md, <i>Failure</i>; T6.4).
 */
public final class FrameworkLaneTransport implements LaneTransport {

    /** Large enough that an overflow means "a lane stopped draining", not "main emitted a lot before the first tick". */
    public static final int DEFAULT_CAPACITY = 65_536;

    /** What crosses a lane: an immutable event and where it came from. Deeply immutable, so it is never copied. */
    public record Message(RecordValue event, Origin origin) {}

    /** Where lanes are seated: the framework's logic lane in an application, a bare tick in a test. */
    public interface Seats {
        /** The inbox {@code name} on {@code lane}, which hears exactly {@code hears}. */
        Inbox inbox(String name, String lane, Topic<?>... hears);

        /** A sender for a publish from outside any delivery, which waits for the next tick. */
        <T> Sender<T> outside(Topic<T> topic);
    }

    /** The framework's seats: {@code Inboxes} for the lanes, {@code Senders} for what arrives from outside. */
    public static Seats seats(Shell shell) {
        return new Seats() {
            @Override
            public Inbox inbox(String name, String lane, Topic<?>... hears) {
                return Inboxes.of(shell, name, lane, hears);
            }

            @Override
            public <T> Sender<T> outside(Topic<T> topic) {
                return shell.senders().to(topic);
            }
        };
    }

    /** One lane: its inbox, its two topics, and its senders to every lane's topics. */
    private static final class Lane {
        final String name;
        final Topic<Message> edges;
        final Topic<Message> samples;
        Inbox inbox;
        /** Keyed by topic name; a TreeMap so nothing here iterates in a salted order. */
        final Map<String, Sender<Message>> senders = new TreeMap<>();

        Lane(String name) {
            this.name = name;
            String topic = "pontif.lane." + (MAIN.equals(name) ? "main" : name);
            this.edges = Topic.edge(topic, Message.class);
            this.samples = Topic.state(topic + ".samples", Message.class, m -> m.event().typeName());
        }
    }

    private final Map<String, Lane> lanes = new TreeMap<>();
    private final Map<String, Sender<Message>> outside = new TreeMap<>();
    /** The lane whose delivery this thread is inside, or null on a thread serving none. Read only by this class. */
    private final ThreadLocal<Lane> serving = new ThreadLocal<>();
    private final Seats seats;

    private volatile Fire fire;
    /** The thread running {@code main}, until it returns: the one place an event folds inline. */
    private volatile Thread runningMain;

    /**
     * @param conductors the program's conductors seated {@code over thread}, each of which gets a lane of its own
     * @param seats      where the lanes' inboxes are made
     * @param capacity   how many edges one lane may have queued before it is a fault
     */
    public FrameworkLaneTransport(Set<String> conductors, Seats seats, int capacity) {
        this.seats = seats;
        lanes.put(MAIN, new Lane(MAIN));
        for (String c : conductors) lanes.put(c, new Lane(c));

        for (Lane lane : lanes.values()) {
            String inbox = inboxName(lane.name);
            String seat = MAIN.equals(lane.name) ? Component.DEFAULT_LANE : lane.name;
            lane.inbox = seats.inbox(inbox, seat, lane.edges, lane.samples);
            lane.inbox.subscribe(inbox + "#edges", lane.edges, m -> deliver(lane, m), capacity, null);
            lane.inbox.subscribe(inbox + "#samples", lane.samples, m -> deliver(lane, m), capacity, null);
        }
        for (Lane from : lanes.values()) {
            for (Lane to : lanes.values()) {
                from.senders.put(to.edges.name(), from.inbox.sender(to.edges));
                from.senders.put(to.samples.name(), from.inbox.sender(to.samples));
            }
        }
        for (Lane to : lanes.values()) {
            outside.put(to.edges.name(), seats.outside(to.edges));
            outside.put(to.samples.name(), seats.outside(to.samples));
        }
    }

    /** As above, seated on {@code shell}'s logic lane with {@link #DEFAULT_CAPACITY}. */
    public FrameworkLaneTransport(Set<String> conductors, Shell shell) {
        this(conductors, seats(shell), DEFAULT_CAPACITY);
    }

    /** The inbox a lane is seated as, which names its component in the bus graph and in a thread's rows. */
    public static String inboxName(String lane) {
        return "pontif." + (MAIN.equals(lane) ? "main" : lane);
    }

    @Override
    public void start(Set<String> conductors, Fire fire) {
        if (!lanes.keySet().containsAll(conductors) || lanes.size() != conductors.size() + 1) {
            throw new IllegalStateException("the program seats " + conductors + " over thread, but its wiring was built "
                    + "for " + lanes.keySet() + ": the lanes are fixed when the application is wired");
        }
        this.fire = fire;
        this.runningMain = Thread.currentThread();
    }

    private void deliver(Lane lane, Message m) {
        Lane outer = serving.get();
        serving.set(lane);
        try {
            fire.fire(m.event(), m.origin());
        } finally {
            if (outer == null) serving.remove(); else serving.set(outer);
        }
    }

    @Override
    public boolean onLane(String lane) {
        return MAIN.equals(lane) && serving.get() == null && Thread.currentThread() == runningMain;
    }

    @Override
    public void send(String lane, RecordValue event, Origin origin, Loss loss) {
        Lane to = lanes.get(lane);
        if (to == null) throw new IllegalStateException("no lane '" + lane + "' was wired");
        String topic = (loss == Loss.SAMPLE ? to.samples : to.edges).name();
        Lane from = serving.get();
        (from != null ? from.senders : outside).get(topic).send(new Message(event, origin));
    }

    /**
     * Where an Instrument's command leaves the program (docs/orchestration.md, the coordinated glossary): a sender
     * on {@code topic} for every lane, so a command a handler sends goes out through the inbox whose delivery made
     * it, at the round's end and in its order, as every other send does (T4.5). A command sent from outside any
     * delivery is an ordinary publish. A widget write is the usual one: a state topic keyed by the widget, which a
     * mailbox on the GUI thread applies in the frame (T3.8).
     *
     * <p>Made while the application is wired, before the first tick: the senders are fixed then, like the lanes.
     */
    public <T> Outlet<T> outlet(Topic<T> topic) {
        Map<String, Sender<T>> byLane = new TreeMap<>();
        for (Lane lane : lanes.values()) byLane.put(lane.name, lane.inbox.sender(topic));
        return new Outlet<>(byLane, seats.outside(topic), serving);
    }

    /** A sender on one topic from whichever lane is sending: see {@link #outlet}. */
    public static final class Outlet<T> {
        private final Map<String, Sender<T>> byLane;
        private final Sender<T> outside;
        private final ThreadLocal<Lane> serving;

        private Outlet(Map<String, Sender<T>> byLane, Sender<T> outside, ThreadLocal<Lane> serving) {
            this.byLane = byLane;
            this.outside = outside;
            this.serving = serving;
        }

        public void send(T command) {
            Lane from = serving.get();
            (from != null ? byLane.get(from.name) : outside).send(command);
        }
    }

    /** {@code main} has returned: from now on every event goes through the tick. */
    @Override
    public void drive() {
        runningMain = null;
    }

    /** The tick outlives {@code main}: a click after it returns must still reach the lane that owns it. */
    @Override
    public boolean survivesMain() {
        return true;
    }
}
