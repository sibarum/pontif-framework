package sibarum.pontif.host;

import sibarum.atchung.Topic;
import sibarum.pontif.ir.LaneTransport;

import java.util.Set;
import java.util.TreeSet;

/**
 * A Pontif program's message graph, as the bus-graph text (vexelray-inspector {@code docs/bus-graph.md}, version 2):
 * what generated wiring answers as {@code BUS_GRAPH}, written here because the program is only known at run time. The
 * Inspector draws it as the graph panel, and {@code ottermate} prints it.
 *
 * <p><b>The lanes.</b> Each lane is a {@code component} part named as its inbox is ({@code pontif.main},
 * {@code pontif.<conductor>}), on the logic lane or on its own declared one, hearing its edge and sample topics by
 * mailbox. Each {@code sends} every lane's topics, because that is what its wiring hands it: a sender to each lane,
 * as a component's constructor declares a {@code Sender} it may or may not use. Which of those a program's handlers
 * actually use is a property of their bodies, and an emit inside a helper or of a computed event is not visible
 * without following calls, so the graph says what the wiring can do and does not guess at less.
 *
 * <p><b>What an extension adds.</b> A GUI extension's window is a {@code view}, and an Instrument's commands leave
 * every lane on a topic of their own ({@link #instrument}). An extension declares those through the wiring, beside
 * the host it installs. The framework's own senders (a frame hook's beats, input, a widget's events) need no line.
 *
 * <p>Each section is sorted, so the same program gives the same text.
 */
public final class PontifBusGraph {

    /** The first line of every version-2 graph. */
    public static final String HEADER = "# vexelray bus graph 2";

    private final Set<String> lanes = new TreeSet<>();
    private final Set<String> parts = new TreeSet<>();
    private final Set<String> hears = new TreeSet<>();
    private final Set<String> sends = new TreeSet<>();
    private final Set<String> topics = new TreeSet<>();

    /** The graph of a program seating {@code conductors} over thread, each lane holding {@code capacity} edges. */
    public PontifBusGraph(Set<String> conductors, int capacity) {
        lanes.add(LaneTransport.MAIN);
        lanes.addAll(conductors);
        for (String lane : lanes) {
            String part = FrameworkLaneTransport.inboxName(lane);
            String seat = FrameworkLaneTransport.seat(lane);
            parts.add("part component " + part + " lane="
                    + (seat.equals(dev.vexelray.framework.api.Component.DEFAULT_LANE) ? "logic" : seat));
            hears(part + "#edges", FrameworkLaneTransport.edges(lane), capacity);
            hears(part + "#samples", FrameworkLaneTransport.samples(lane), capacity);
            sendsToLanes(part);
        }
    }

    /** A view on the GUI thread, named {@code part}: the window of a GUI extension. */
    public PontifBusGraph view(String part) {
        parts.add("part view " + part + " lane=gui");
        return this;
    }

    /** {@code mailbox} ({@code part#method}) hears {@code topic}, holding at most {@code capacity} edges. */
    public PontifBusGraph hears(String mailbox, Topic<?> topic, int capacity) {
        hears.add("hears " + mailbox + " topic=" + topic.name() + " by=mailbox capacity=" + capacity);
        return topic(topic);
    }

    /** {@code part} sends on {@code topic}. */
    public PontifBusGraph sends(String part, Topic<?> topic) {
        sends.add("sends " + part + " topic=" + topic.name());
        return topic(topic);
    }

    /** {@code part} sends into the program: an event reaches whichever lane owns it, so every lane's topics. */
    public PontifBusGraph sendsToLanes(String part) {
        for (String lane : lanes) {
            sends(part, FrameworkLaneTransport.edges(lane));
            sends(part, FrameworkLaneTransport.samples(lane));
        }
        return this;
    }

    /** An Instrument's commands leave on {@code topic} from whichever lane's handler sends them: every lane sends it. */
    public PontifBusGraph instrument(Topic<?> topic) {
        for (String lane : lanes) sends(FrameworkLaneTransport.inboxName(lane), topic);
        return this;
    }

    private PontifBusGraph topic(Topic<?> topic) {
        String kind = topic.kind() == Topic.Kind.STATE ? "state" : topic.keyed() ? "edge keyed" : "edge";
        topics.add("topic " + topic.name() + " kind=" + kind);
        return this;
    }

    /** The text, header first, then parts, hears, sends and topics, each sorted, and a newline after every line. */
    public String text() {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (Set<String> section : java.util.List.of(parts, hears, sends, topics)) {
            for (String line : section) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
