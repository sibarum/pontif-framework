package sibarum.pontif.net;

import org.junit.jupiter.api.Test;
import sibarum.pontif.net.debug.DebugServer;
import sibarum.pontif.net.debug.DebugSession;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link ProgramLauncher} the way the editor does: a loopback {@link DebugServer} is stood
 * up, then the launcher is spawned as a real child JVM (same {@code java -cp <java.class.path>}
 * mechanism as {@code App.launchProgram}) with {@link DebugSession#PORT_ENV} set and its merged
 * stdout/stderr drained to EOF. Asserts the child announces itself, runs, mirrors its {@code
 * StdOut} emit, and reports completion — and exits 0. This covers the out-of-process run path that
 * ordinary (non-GUI) editor runs now take.
 */
class ProgramLauncherIntegrationTest {

    private static final String PROGRAM = """
            requires pontif.events.{Event, StdOut}
            struct Tick(n:Int)
            assign trait Tick:Event{}
            action log(e:Tick) -> emit StdOut("hit-from-subprocess")  e
            main ( emit Tick(42)  0 )
            """;

    @Test
    void subprocessRunStreamsTelemetryAndExitsZero() throws Exception {
        List<String> stdout = new CopyOnWriteArrayList<>();
        AtomicReference<String> helloSource = new AtomicReference<>();
        AtomicReference<String> completed = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        DebugServer.Listener listener = new DebugServer.Listener() {
            @Override public void onHello(long pid, String source) { helloSource.set(source); }
            @Override public void onStdout(String text) { stdout.add(text); }
            @Override public void onRunCompleted(String resultText) {
                completed.set(resultText);
                done.countDown();
            }
        };

        Path tmp = Files.createTempFile("pontif-launcher-test-", ".ptf");
        Files.writeString(tmp, PROGRAM, StandardCharsets.UTF_8);
        try (DebugServer server = DebugServer.start(listener)) {
            String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            ProcessBuilder pb = new ProcessBuilder(
                    javaBin,
                    "-cp", System.getProperty("java.class.path"),
                    "sibarum.pontif.net.ProgramLauncher",
                    tmp.toString(),
                    "",                 // no resolveDir
                    "launcher-test.ptf");
            pb.environment().put(DebugSession.PORT_ENV, Integer.toString(server.port()));
            pb.redirectErrorStream(true);

            Process proc = pb.start();
            StringBuilder merged = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    merged.append(line).append('\n');
                }
            }
            boolean exited = proc.waitFor(30, TimeUnit.SECONDS);
            assertTrue(exited, "launcher subprocess should exit; output so far:\n" + merged);
            assertEquals(0, proc.exitValue(), "clean run exits 0; output:\n" + merged);

            assertTrue(done.await(5, TimeUnit.SECONDS),
                    "run-completed telemetry should arrive; output:\n" + merged);
        } finally {
            Files.deleteIfExists(tmp);
        }

        assertEquals("launcher-test.ptf", helloSource.get());
        assertEquals("0", completed.get(), "main returns 0");
        assertTrue(stdout.contains("hit-from-subprocess"),
                "the StdOut emit should be mirrored over the debug port; got " + stdout);
    }
}
