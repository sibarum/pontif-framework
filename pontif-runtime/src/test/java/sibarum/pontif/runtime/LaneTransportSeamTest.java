package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.LaneTransport;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seam the lane code was lifted behind (docs/orchestration.md, "one pattern, four transports"): the
 * interpreter decides which lane owns an event, and a {@link LaneTransport} decides how it gets there.
 * A host that owns its own threads and mailboxes — a container — plugs in here, so the interpreter must
 * work against a transport it has never heard of. This one is the opposite of the default: no threads at
 * all, every lane served cooperatively by {@code drive()}.
 */
class LaneTransportSeamTest {

    private static final String PROGRAM = """
            requires pontif.events.{Event, StdOut}
            struct Command(n:Int)
            assign trait Command:Event{}
            struct Status(text:String)
            assign trait Status:Event{}
            conductor App { onCommand(c:Command) -> emit Status("done ")  c }
            conductor Display { onStatus(s:Status) -> emit StdOut(s.text)  s }
            spawn App over thread
            spawn Display over thread
            main ( emit Command(1)  emit Command(2)  0 )
            """;

    /** Queues every send and serves them in order on the calling thread: one lane's worth of threads, none of them new. */
    private static final class Cooperative implements LaneTransport {
        final Set<String> seated = new java.util.TreeSet<>();
        final List<String> sentTo = new ArrayList<>();
        private record Task(String lane, Runnable run) {}
        private final Queue<Task> pending = new ArrayDeque<>();
        private String serving;   // the lane whose task is running now — the only lane the caller is on
        private Fire fire;

        @Override public void start(Set<String> conductors, Fire fire) {
            seated.addAll(conductors);
            this.fire = fire;
        }

        @Override public boolean onLane(String lane) {
            return lane.equals(serving);   // a lane is the caller's only while its own task runs
        }

        @Override public void send(String lane, RecordValue event, Origin origin) {
            sentTo.add(lane);
            pending.add(new Task(lane, () -> fire.fire(event, origin)));
        }

        @Override public void drive() {
            for (Task t; (t = pending.poll()) != null; ) {
                serving = t.lane();
                t.run().run();
            }
            serving = null;
        }
    }

    @Test
    void aHostTransportCarriesTheProgram_andTheInterpreterNeverStartsAThread() {
        var compiled = new PontifCompiler().compile(PROGRAM, "seam.ptf");
        var program = ((PontifCompiler.CompileResult.Compiled) compiled).program();
        Cooperative transport = new Cooperative();
        Set<Thread> before = Thread.getAllStackTraces().keySet();

        PrintStream orig = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            new IrInterpreter(program.simplifier()).laneTransport(() -> transport).eval(program.module());
        } finally {
            System.setOut(orig);
        }

        assertEquals(Set.of("App", "Display"), transport.seated, "the transport is told every seated conductor");
        String printed = out.toString(StandardCharsets.UTF_8);
        assertEquals(2, printed.split("done ", -1).length - 1,
                "both commands hopped App → Display → main through the host's transport: " + printed);
        assertTrue(transport.sentTo.contains("App") && transport.sentTo.contains("Display")
                        && transport.sentTo.contains(LaneTransport.MAIN),
                "events were routed to each lane by name: " + transport.sentTo);
        assertFalse(Thread.getAllStackTraces().keySet().stream()
                        .anyMatch(t -> !before.contains(t) && t.getName().startsWith("pontif-conductor-")),
                "the default thread tier was never stood up");
    }
}
