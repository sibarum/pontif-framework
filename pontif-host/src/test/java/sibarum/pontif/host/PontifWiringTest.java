package sibarum.pontif.host;

import dev.vexelray.framework.shell.AppInfo;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.CompiledProgram;
import sibarum.pontif.runtime.PontifCompiler;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A whole Pontif program as a framework application, built headlessly through {@code VexelApplication.tree}:
 * two {@code over thread} conductors become two components, the interpreter hands events between them through
 * the framework's mailboxes, and the main conductor is served by the frame hook rather than a drive loop.
 */
class PontifWiringTest {

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
            main ( emit Command(1)  emit Command(2)  emit Command(3)  0 )
            """;

    @Test
    void conductorsAreComponents_andTheMainLaneIsServedInTheFrame() throws Exception {
        var compiled = new PontifCompiler().compile(PROGRAM, "hosted.ptf");
        CompiledProgram program = ((PontifCompiler.CompileResult.Compiled) compiled).program();
        PontifWiring wiring = new PontifWiring(new AppInfo("pontif-host-test", "Pontif", 640, 480), program);

        PrintStream orig = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Shell shell = null;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            shell = VexelApplication.tree(wiring, new String[0]);

            // The hops App -> Display -> main happen on component threads; the last one lands in the main
            // lane's mailbox, which only a frame's APP stage (here: this thread) serves.
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (printed(out).split("done ", -1).length - 1 < 3) {
                wiring.lanes().mainDrain().run();
                if (System.nanoTime() > deadline) break;
                Thread.sleep(2);
            }
        } finally {
            System.setOut(orig);
            if (shell != null) shell.disposer().close();
        }

        assertEquals(3, printed(out).split("done ", -1).length - 1,
                "every command crossed App and Display and rendered on the main lane: " + printed(out));
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().startsWith("pontif-conductor-")),
                "no interpreter-owned thread was started; the framework's lanes carried it");
    }

    private static String printed(ByteArrayOutputStream out) {
        return out.toString(StandardCharsets.UTF_8);
    }
}
