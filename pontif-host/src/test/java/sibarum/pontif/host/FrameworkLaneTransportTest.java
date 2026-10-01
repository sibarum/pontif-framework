package sibarum.pontif.host;

import dev.vexelray.framework.core.Lanes;
import dev.vexelray.framework.shell.Placement;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.ir.LaneTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transport against the framework's real pieces - a bus, lanes and standalone placements - with no
 * interpreter and no window: which thread a delivery runs on, what a thread owns, what a crash does, and what
 * wakes the loop.
 */
class FrameworkLaneTransportTest {

    private static final RecordValue EVENT = new RecordValue("Ping", Map.of());

    private final Atchung bus = Atchung.create();
    private final Lanes lanes = new Lanes();
    private final List<Placement> placements = new ArrayList<>();
    private final List<RuntimeException> crashes = new CopyOnWriteArrayList<>();

    private FrameworkLaneTransport transport() {
        return new FrameworkLaneTransport(bus, name -> {
            Placement p = new Placement(name, bus, lanes);
            placements.add(p);
            return p;
        }, crashes::add, 64);
    }

    private void startAll() {
        placements.forEach(Placement::start);
    }

    private void stopAll() {
        placements.forEach(Placement::close);
        lanes.close();
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(2);
        }
    }

    @Test
    void aConductorRunsOnItsOwnThread_andTheCallerOwnsOnlyTheMainLane() throws Exception {
        FrameworkLaneTransport transport = transport();
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        AtomicBoolean ownedItself = new AtomicBoolean();
        AtomicBoolean ownedMain = new AtomicBoolean(true);
        transport.start(Set.of("Meter"), (event, origin) -> {
            ranOn.set(Thread.currentThread());
            ownedItself.set(transport.onLane("Meter"));
            ownedMain.set(transport.onLane(LaneTransport.MAIN));
        });
        startAll();
        try {
            assertTrue(transport.onLane(LaneTransport.MAIN), "the thread that started the run is the main lane");
            assertFalse(transport.onLane("Meter"), "and does not own a conductor's lane");

            transport.send("Meter", EVENT, Origin.NONE);
            await(() -> ranOn.get() != null, "the delivery");

            assertNotEquals(Thread.currentThread(), ranOn.get(), "a conductor never runs on the sender's thread");
            assertEquals("vexel-component-Meter", ranOn.get().getName(),
                    "it runs on the framework's thread for that component, named in a thread dump");
            assertTrue(ownedItself.get(), "inside its own delivery the lane owns the thread, so its emits fold inline");
            assertFalse(ownedMain.get(), "and the main lane is not that thread's");
        } finally {
            stopAll();
        }
    }

    @Test
    void theMainLaneIsServedByTheCallerOnDrain_andASendFromAnotherThreadWakesTheLoop() throws Exception {
        FrameworkLaneTransport transport = transport();
        List<Thread> servedOn = new CopyOnWriteArrayList<>();
        transport.start(Set.of("Meter"), (event, origin) -> servedOn.add(Thread.currentThread()));
        int[] wakes = {0};
        transport.onWake(() -> wakes[0]++);
        startAll();
        try {
            Thread sender = new Thread(() -> transport.send(LaneTransport.MAIN, EVENT, Origin.NONE));
            sender.start();
            sender.join();

            assertEquals(1, wakes[0], "an event for the main lane from another thread wakes the frame loop");
            assertTrue(servedOn.isEmpty(), "and is not delivered until the main thread drains: nothing else may fold it");

            transport.mainDrain().run();
            assertEquals(List.of(Thread.currentThread()), servedOn, "the drain runs it on the main thread, in the frame");
        } finally {
            stopAll();
        }
    }

    @Test
    void aHandlerThatThrowsIsACrash_notSomethingTheLaneSwallows() throws Exception {
        FrameworkLaneTransport transport = transport();
        RuntimeException boom = new IllegalStateException("handler failed");
        transport.start(Set.of("Meter"), (event, origin) -> { throw boom; });
        startAll();
        try {
            transport.send("Meter", EVENT, Origin.NONE);
            await(() -> !crashes.isEmpty(), "the crash to be reported");
            assertEquals(List.of(boom), crashes, "the failure goes to the crash policy, which halts in production");
        } finally {
            stopAll();
        }
    }
}
