package sibarum.pontif.net.debug;

import org.junit.jupiter.api.Test;
import sibarum.elektro.queue.dyn.DynValue;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the whole debug port end to end within one JVM: a {@link DebugServer} on loopback, a
 * {@link DebugSession} dialing it, and a real interpreter run whose {@code emit} fan-out is
 * mirrored back as typed telemetry. This exercises the elektro-Q dynamic codec, the interpreter
 * event seam, and the {@link sibarum.pontif.net.PontifDyn} bridge together.
 */
class DebugPortIntegrationTest {

    private static final String PROGRAM = """
            requires pontif.events.{Event, StdOut}
            struct Tick(n:Int)
            assign trait Tick:Event{}
            action log(e:Tick) -> emit StdOut("hit")  e
            main ( emit Tick(42)  0 )
            """;

    @Test
    void programTelemetryReachesEditor() throws Exception {
        List<String> stdout = new CopyOnWriteArrayList<>();
        List<String> events = new CopyOnWriteArrayList<>();
        List<String> actions = new CopyOnWriteArrayList<>();
        AtomicReference<String> helloSource = new AtomicReference<>();
        AtomicReference<DynValue> tickPayload = new AtomicReference<>();
        AtomicReference<String> completed = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        DebugServer.Listener listener = new DebugServer.Listener() {
            @Override public void onHello(long pid, String source) { helloSource.set(source); }
            @Override public void onStdout(String text) { stdout.add(text); }
            @Override public void onActionFired(String reactionName, String eventType) {
                actions.add(reactionName + "->" + bare(eventType));
            }
            @Override public void onEvent(long seq, String typeName, DynValue payload) {
                events.add(seq + ":" + bare(typeName));
                if (bare(typeName).equals("Tick")) tickPayload.set(payload);
            }
            @Override public void onRunCompleted(String resultText) {
                completed.set(resultText);
                done.countDown();
            }
        };

        try (DebugServer server = DebugServer.start(listener)) {
            DebugSession session = DebugSession.attach(server.port(), "test.ptf");
            try {
                PontifCompiler compiler = new PontifCompiler();
                PontifRunner runner = new PontifRunner();
                PontifRunner.RunResult result =
                        runner.run(compiler.compileAlt(PROGRAM, "test.ptf"), PontifRunner.Engine.INTERPRETER);
                if (result.isError()) {
                    session.runFailed(result.text(), 0, 0);
                    throw new AssertionError("program failed to run: " + result.text());
                }
                session.runCompleted(result.text());
            } finally {
                session.close();
            }

            assertTrue(done.await(5, TimeUnit.SECONDS), "run-completed telemetry should arrive");
        }

        assertEquals("test.ptf", helloSource.get());
        assertEquals("0", completed.get());
        assertTrue(stdout.contains("hit"), "stdout mirror should carry the StdOut emit; got " + stdout);
        assertTrue(events.stream().anyMatch(e -> e.endsWith(":Tick")), "Tick event telemetry; got " + events);
        assertTrue(actions.contains("log->Tick"), "action-fired telemetry; got " + actions);

        // The dynamic payload decoded to a struct whose 'n' field is the Int 42.
        DynValue.Struct tick = assertInstanceOf(DynValue.Struct.class, tickPayload.get());
        assertEquals(new DynValue.I64(42), tick.fields().get("n"));
    }

    private static String bare(String typeName) {
        int slash = typeName.lastIndexOf('/');
        return slash < 0 ? typeName : typeName.substring(slash + 1);
    }
}
