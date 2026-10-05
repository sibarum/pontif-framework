package sibarum.pontif.host;

import dev.vexelray.framework.shell.AppInfo;
import dev.vexelray.framework.shell.Placements;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.framework.shell.Wiring;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.ir.LaneTransport;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transport against the framework's real pieces - a bus, lanes and placements made the way generated code
 * makes them - with no interpreter and no window: which thread a delivery runs on, what a thread owns, what a
 * crash does, what wakes the loop, and what a declared sample does to a queue.
 *
 * <p>Placement is the container's now, so the harness is a throwaway application: its {@code TREE} phase starts
 * the transport and may send before any lane runs, and the framework starts every lane when
 * {@code VexelApplication.tree} returns - the order a real program meets.
 */
class FrameworkLaneTransportTest {

    private static final RecordValue EVENT = new RecordValue("Ping", Map.of());

    private final List<RuntimeException> crashes = new CopyOnWriteArrayList<>();
    private FrameworkLaneTransport transport;
    private Shell shell;

    private void application(Set<String> conductors, LaneTransport.Fire fire,
                             Consumer<FrameworkLaneTransport> beforeLanesStart) {
        shell = VexelApplication.tree(new Wiring() {
            @Override public AppInfo info() {
                return new AppInfo("pontif-host-test", "Pontif host test", 320, 240);
            }

            @Override public void model(Shell s) {
                transport = new FrameworkLaneTransport(s.bus(), lane -> Placements.of(s, lane), crashes::add, 64);
            }

            @Override public void tree(Shell s) {
                transport.start(conductors, fire);
                beforeLanesStart.accept(transport);
            }
        }, new String[0]);
    }

    @AfterEach
    void stop() {
        if (shell != null) shell.disposer().close();
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
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        AtomicBoolean ownedItself = new AtomicBoolean();
        AtomicBoolean ownedMain = new AtomicBoolean(true);
        application(Set.of("Meter"), (event, origin) -> {
            ranOn.set(Thread.currentThread());
            ownedItself.set(transport.onLane("Meter"));
            ownedMain.set(transport.onLane(LaneTransport.MAIN));
        }, t -> { });

        assertTrue(transport.onLane(LaneTransport.MAIN), "the thread that started the run is the main lane");
        assertFalse(transport.onLane("Meter"), "and does not own a conductor's lane");

        transport.send("Meter", EVENT, Origin.NONE, LaneTransport.Loss.EDGE);
        await(() -> ranOn.get() != null, "the delivery");

        assertNotEquals(Thread.currentThread(), ranOn.get(), "a conductor never runs on the sender's thread");
        assertEquals("vexel-component-Meter", ranOn.get().getName(),
                "it runs on the framework's thread for that component, named in a thread dump");
        assertTrue(ownedItself.get(), "inside its own delivery the lane owns the thread, so its emits fold inline");
        assertFalse(ownedMain.get(), "and the main lane is not that thread's");
    }

    @Test
    void theMainLaneIsServedByTheCallerOnDrain_andASendFromAnotherThreadWakesTheLoop() throws Exception {
        List<Thread> servedOn = new CopyOnWriteArrayList<>();
        application(Set.of("Meter"), (event, origin) -> servedOn.add(Thread.currentThread()), t -> { });
        int[] wakes = {0};
        transport.onWake(() -> wakes[0]++);

        Thread sender = new Thread(() ->
                transport.send(LaneTransport.MAIN, EVENT, Origin.NONE, LaneTransport.Loss.EDGE));
        sender.start();
        sender.join();

        assertEquals(1, wakes[0], "an event for the main lane from another thread wakes the frame loop");
        assertTrue(servedOn.isEmpty(), "and is not delivered until the main thread drains: nothing else may fold it");

        transport.mainDrain().run();
        assertEquals(List.of(Thread.currentThread()), servedOn, "the drain runs it on the main thread, in the frame");
    }

    @Test
    void aHandlerThatThrowsIsACrash_notSomethingTheLaneSwallows() throws Exception {
        RuntimeException boom = new IllegalStateException("handler failed");
        application(Set.of("Meter"), (event, origin) -> { throw boom; }, t -> { });

        transport.send("Meter", EVENT, Origin.NONE, LaneTransport.Loss.EDGE);
        await(() -> !crashes.isEmpty(), "the crash to be reported");
        assertEquals(List.of(boom), crashes, "the failure goes to the crash policy, which halts in production");
    }

    private static RecordValue event(String type, long n) {
        return new RecordValue(type, Map.of("n", n));
    }

    @Test
    void aQueuedSampleIsSupersededByANewerSampleOfItsType_andNothingElseIsTouched() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        // Sent in TREE, before the lane's thread exists, so what arrives is decided by the mailbox alone.
        application(Set.of("Pointer"),
                (event, origin) -> seen.add(event.typeName() + event.members().get("n")),
                t -> {
                    t.send("Pointer", event("Moved", 1), Origin.NONE, LaneTransport.Loss.SAMPLE);
                    t.send("Pointer", event("Pressed", 1), Origin.NONE, LaneTransport.Loss.EDGE);
                    t.send("Pointer", event("Resized", 1), Origin.NONE, LaneTransport.Loss.SAMPLE);
                    t.send("Pointer", event("Moved", 2), Origin.NONE, LaneTransport.Loss.SAMPLE);
                    t.send("Pointer", event("Pressed", 2), Origin.NONE, LaneTransport.Loss.EDGE);
                    t.send("Pointer", event("Moved", 3), Origin.NONE, LaneTransport.Loss.SAMPLE);
                });

        await(() -> seen.size() >= 4, "the lane to drain");
        Thread.sleep(50);
        assertEquals(List.of("Pressed1", "Resized1", "Pressed2", "Moved3"), seen,
                "every edge arrives, in order; Moved1 and Moved2 were superseded by Moved3, Resized was not "
                        + "merged with them, and the survivor sits where its newest write was");
    }

    @Test
    void edgesAreNeverFolded_evenOfTheSameType() throws Exception {
        List<Object> seen = new CopyOnWriteArrayList<>();
        application(Set.of("Pointer"), (event, origin) -> seen.add(event.members().get("n")),
                t -> {
                    for (long n = 1; n <= 5; n++) {
                        t.send("Pointer", event("Pressed", n), Origin.NONE, LaneTransport.Loss.EDGE);
                    }
                });

        await(() -> seen.size() >= 5, "the lane to drain");
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), seen, "five presses are five events");
    }
}
