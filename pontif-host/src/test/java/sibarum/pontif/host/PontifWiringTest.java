package sibarum.pontif.host;

import dev.vexelray.framework.shell.AppInfo;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.runtime.CompiledProgram;
import sibarum.pontif.runtime.PontifCompiler;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A whole Pontif program as a framework application, built headlessly through {@code VexelApplication.tree}:
 * two {@code over thread} conductors become two components on lanes of their own, the interpreter hands events
 * between them through the logic tick's inboxes, and {@code main}'s lane is an inbox on the logic lane, served by
 * the framework and never by the main thread.
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
    void conductorsAreComponents_andTheTickCarriesEveryHop() throws Exception {
        var compiled = new PontifCompiler().compile(PROGRAM, "hosted.ptf");
        CompiledProgram program = ((PontifCompiler.CompileResult.Compiled) compiled).program();
        PontifWiring wiring = new PontifWiring(new AppInfo("pontif-host-test", "Pontif", 640, 480), program);

        PrintStream orig = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Shell shell = null;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            shell = VexelApplication.tree(wiring, new String[0]);

            // The hops App -> Display -> main happen on the conductors' declared lanes; the last one lands in
            // main's inbox on the logic lane. Nobody has to drain anything: the framework's tick runs them all.
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (printed(out).split("done ", -1).length - 1 < 3) {
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

    /**
     * Loss classes end to end. {@code main} emits before any lane runs, so the pointer's mailbox holds the whole
     * burst when the framework starts it; {@code Moved} declares itself a {@code Sample} and {@code Pressed} does
     * not, so the readings collapse to the newest while the press survives, in the order the survivors were written.
     */
    private static final String SAMPLED = """
            requires pontif.events.{Event, Sample, StdOut}
            struct Moved(x:Int)
            assign trait Moved:Event{}
            assign trait Moved:Sample{}
            struct Pressed(n:Int)
            assign trait Pressed:Event{}
            conductor Pointer {
              onMoved(m:Moved) -> emit StdOut("m" + m.x + " ")  m
              onPressed(p:Pressed) -> emit StdOut("p" + p.n + " ")  p
            }
            spawn Pointer over thread
            main ( emit Moved(1)  emit Moved(2)  emit Pressed(1)  emit Moved(3)  0 )
            """;

    @Test
    void aSampleIsSupersededInAQueuedLane_andAnEdgeIsNot() throws Exception {
        var compiled = new PontifCompiler().compile(SAMPLED, "sampled.ptf");
        if (compiled instanceof PontifCompiler.CompileResult.Failed f) throw new AssertionError(f.error().text());
        CompiledProgram program = ((PontifCompiler.CompileResult.Compiled) compiled).program();
        PontifWiring wiring = new PontifWiring(new AppInfo("pontif-host-test", "Pontif", 640, 480), program);

        PrintStream orig = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Shell shell = null;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            shell = VexelApplication.tree(wiring, new String[0]);

            long deadline = System.nanoTime() + 5_000_000_000L;
            while (printed(out).split(" ", -1).length - 1 < 2 && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            Thread.sleep(50);
        } finally {
            System.setOut(orig);
            if (shell != null) shell.disposer().close();
        }

        assertEquals("p1 m3 ", printed(out),
                "Moved(1) and Moved(2) were superseded by Moved(3); the press was an edge and arrived, first, "
                        + "because the surviving reading sits where its newest write was");
    }

    private static final String CONDUCTED = """
            requires pontif.events.{Event, StdOut}
            requires pontif.orchestra.{conduct, Tick, Fixed}
            conductor Clock { onTick(t:Tick) -> emit StdOut("t" + t.n + " ")  t }
            spawn Clock over thread
            main ( conduct(3, Fixed(30)) )
            """;

    @Test
    void conductPacesOnTheFrameworksLoop_insteadOfBlockingOneOfItsOwn() throws Exception {
        var compiled = new PontifCompiler().compile(CONDUCTED, "conducted.ptf");
        if (compiled instanceof PontifCompiler.CompileResult.Failed f) throw new AssertionError(f.error().text());
        CompiledProgram program = ((PontifCompiler.CompileResult.Compiled) compiled).program();
        PontifWiring wiring = new PontifWiring(new AppInfo("pontif-host-test", "Pontif", 640, 480), program);

        PrintStream orig = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Shell shell = null;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            // Returns: a blocking conduct would never have come back, the headless Conductor being the whole loop.
            shell = VexelApplication.tree(wiring, new String[0]);
            shell.hooks().seal();

            long deadline = System.nanoTime() + 5_000_000_000L;
            while (printed(out).split(" ", -1).length - 1 < 3 && System.nanoTime() < deadline) {
                shell.hooks().run();            // the frame: fires a beat when one is due
                Thread.sleep(2);
            }
        } finally {
            System.setOut(orig);
            if (shell != null) shell.disposer().close();
        }

        assertEquals("t1 t2 t3 ", printed(out), "three beats, 30 ms apart, each reaching the clock's conductor");
    }
    private static final String UNTHREADED = """
            requires pontif.events.{Event, StdOut}
            requires pontif.orchestra.{conduct, Tick, Fixed}
            conductor Clock { onTick(t:Tick) -> emit StdOut("t" + t.n + " ")  t }
            spawn Clock
            main ( conduct(2, Fixed(10)) )
            """;

    /**
     * T7.1, for a program that seats nothing {@code over thread}: its beats are fired by a frame hook on the
     * framework's thread (here, this one), but every handler runs in the logic tick, on {@code vexel-logic}.
     */
    @Test
    void anUnthreadedProgramsHandlersRunInTheLogicTick_notOnTheThreadThatFiredTheEvent() throws Exception {
        var compiled = new PontifCompiler().compile(UNTHREADED, "unthreaded.ptf");
        if (compiled instanceof PontifCompiler.CompileResult.Failed f) throw new AssertionError(f.error().text());
        CompiledProgram program = ((PontifCompiler.CompileResult.Compiled) compiled).program();
        PontifWiring wiring = new PontifWiring(new AppInfo("pontif-host-test", "Pontif", 640, 480), program);

        java.util.Set<String> handlerThreads = java.util.concurrent.ConcurrentHashMap.newKeySet();
        IrInterpreter.installEventListener((event, seq, origin) -> {
            if (event.typeName().endsWith("Tick")) handlerThreads.add(Thread.currentThread().getName());
        });
        PrintStream orig = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Shell shell = null;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            shell = VexelApplication.tree(wiring, new String[0]);
            shell.hooks().seal();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (printed(out).split(" ", -1).length - 1 < 2 && System.nanoTime() < deadline) {
                shell.hooks().run();
                Thread.sleep(2);
            }
        } finally {
            IrInterpreter.installEventListener(null);
            System.setOut(orig);
            if (shell != null) shell.disposer().close();
        }

        assertEquals("t1 t2 ", printed(out));
        assertEquals(java.util.Set.of("vexel-logic"), handlerThreads,
                "every beat was handled in the logic tick, none on the thread that fired it");
    }
}
