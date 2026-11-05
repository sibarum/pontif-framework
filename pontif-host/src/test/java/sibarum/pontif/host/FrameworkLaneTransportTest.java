package sibarum.pontif.host;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Inbox;
import sibarum.atchung.Sender;
import sibarum.atchung.Tick;
import sibarum.atchung.Topic;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.ir.LaneTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.pontif.ir.LaneTransport.MAIN;

/**
 * The transport on a bare atchung tick, run by hand on this thread: what folds inline and what waits for a round,
 * what a lane's own emit does, what a declared sample does to a queue, and what a crash does. No interpreter and
 * no application; {@code PontifWiringTest} runs whole programs on the framework's logic lane.
 */
class FrameworkLaneTransportTest {

    private Tick tick;
    private FrameworkLaneTransport transport;
    private final List<String> fired = new ArrayList<>();
    private Consumer<RecordValue> onFire = e -> { };

    @BeforeEach
    void seat() {
        Atchung bus = Atchung.create();
        tick = bus.tick(null);
        Inbox outsider = tick.inbox("outside");
        transport = new FrameworkLaneTransport(Set.of("Pointer"), new FrameworkLaneTransport.Seats() {
            @Override
            public Inbox inbox(String name, String lane, Topic<?>... hears) {
                return tick.inbox(name);
            }

            @Override
            public <T> Sender<T> outside(Topic<T> topic) {
                return outsider.sender(topic);
            }
        }, 64);
        transport.start(Set.of("Pointer"), (event, origin) -> {
            fired.add(event.typeName() + event.members().getOrDefault("n", ""));
            onFire.accept(event);
        });
    }

    private static RecordValue event(String type, long n) {
        return new RecordValue(type, Map.of("n", n));
    }

    @Test
    void mainFoldsInlineOnlyUntilItReturns() {
        assertTrue(transport.onLane(MAIN), "main's own thread folds main's events while main runs");
        assertFalse(transport.onLane("Pointer"), "a conductor's event is sent even while main runs");
        transport.drive();
        assertFalse(transport.onLane(MAIN), "once main has returned, every event goes through the tick");
    }

    @Test
    void whatMainSendsWaitsForTheFirstTick() {
        transport.send("Pointer", event("Ping", 1), Origin.NONE, LaneTransport.Loss.EDGE);
        assertEquals(List.of(), fired, "nothing is delivered before a tick runs");
        tick.runFor(10);
        assertEquals(List.of("Ping1"), fired);
    }

    @Test
    void aLanesOwnEmitWaitsForTheNextRound_ratherThanFoldingInsideTheDelivery() {
        transport.drive();
        onFire = e -> {
            if (e.typeName().equals("Ping")) {
                assertFalse(transport.onLane(MAIN), "a delivery never folds inline, even for its own lane");
                transport.send(MAIN, event("Pong", 1), Origin.NONE, LaneTransport.Loss.EDGE);
                fired.add("end of Ping");
            }
        };
        transport.send(MAIN, event("Ping", 1), Origin.NONE, LaneTransport.Loss.EDGE);
        tick.runFor(1);
        assertEquals(List.of("Ping1", "end of Ping"), fired, "the Pong was queued for the next round");
        tick.runFor(1);
        assertEquals(List.of("Ping1", "end of Ping", "Pong1"), fired);
    }

    @Test
    void aQueuedSampleIsSupersededByANewerOneOfItsType_andAnEdgeIsNot() {
        transport.send("Pointer", event("Moved", 1), Origin.NONE, LaneTransport.Loss.SAMPLE);
        transport.send("Pointer", event("Moved", 2), Origin.NONE, LaneTransport.Loss.SAMPLE);
        transport.send("Pointer", event("Pressed", 1), Origin.NONE, LaneTransport.Loss.EDGE);
        transport.send("Pointer", event("Pressed", 2), Origin.NONE, LaneTransport.Loss.EDGE);
        transport.send("Pointer", event("Moved", 3), Origin.NONE, LaneTransport.Loss.SAMPLE);
        tick.runFor(10);
        assertEquals(1, fired.stream().filter(f -> f.startsWith("Moved")).count(), "one reading survives: " + fired);
        assertTrue(fired.contains("Moved3"), "and it is the newest: " + fired);
        assertEquals(List.of("Pressed1", "Pressed2"),
                fired.stream().filter(f -> f.startsWith("Pressed")).toList(), "every edge arrives, in order");
    }

    @Test
    void aHandlerThatThrowsIsNotSwallowed() {
        onFire = e -> {
            throw new IllegalStateException("boom");
        };
        transport.send("Pointer", event("Ping", 1), Origin.NONE, LaneTransport.Loss.EDGE);
        assertThrows(IllegalStateException.class, () -> tick.runFor(10),
                "the tick rethrows it, and the framework's fault policy halts");
    }

    @Test
    void aProgramSeatingOtherConductorsThanItsWiringIsRefused() {
        assertThrows(IllegalStateException.class, () -> transport.start(Set.of("Other"), (e, o) -> { }));
    }
}
