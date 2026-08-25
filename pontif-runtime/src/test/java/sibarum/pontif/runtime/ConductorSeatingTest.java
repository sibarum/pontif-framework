package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conductor seating, cut A (docs/orchestration.md, §Seating) — a {@code spawn Conductor} in the
 * entry module <b>activates</b> that conductor: its concrete handlers (method-body form,
 * {@code onTick(e:E) -> …}) become live reactions, so an emitted event reaches them. An <b>unseated</b>
 * conductor is inert — its handlers never fire (libraries define, the entry point activates). This
 * cut is effect-only; mutable conductor state is the next cut.
 */
class ConductorSeatingTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private record Output(String out, String err) {}

    private Output run(String src) {
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            RunResult r = runner.run(compiler.compile(src, "seating.ptf"), Engine.INTERPRETER);
            assertFalse(r.isError(), "program should compile and run: " + r);
            return new Output(out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }

    @Test
    void seatedConductor_handlerFiresOnEmittedEvent() {
        Output o = run("""
                requires pontif.events.{Event, StdOut}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                conductor Meter { onTick(e:Tick) -> emit StdOut("tick ")  e }
                spawn Meter
                main ( emit Tick(1)  0 )
                """);
        assertTrue(o.out().contains("tick "), "the seated conductor's handler fired");
        assertFalse(o.err().contains("dead letter"), "a handled event is not a dead letter");
    }

    @Test
    void seatWithOverThreadPlacement_parsesLinksAndRuns() {
        // `spawn Meter over thread` is the same-process-thread tier (docs/orchestration.md §Seating).
        // The placement parses, links, and runs through the whole pipeline; its runtime effect is
        // currently identical to the main lane (own-thread execution is the next cut) — this guards that
        // the new surface flows end-to-end and stays green while threading is built behind it.
        Output o = run("""
                requires pontif.events.{Event, StdOut}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                conductor Meter { onTick(e:Tick) -> emit StdOut("tick ")  e }
                spawn Meter over thread
                main ( emit Tick(1)  0 )
                """);
        assertTrue(o.out().contains("tick "), "an `over thread`-seated conductor's handler fires");
        assertFalse(o.err().contains("dead letter"), "a handled event is not a dead letter");
    }

    @Test
    void overThreadSeat_recordsThreadTier_bareSeatDoesNot() {
        // Cut 3a: the seating placement survives compilation onto the CompiledModule, so the interpreter
        // can later decide inline-vs-daemon. `spawn Meter over thread` lands Meter in threadedConductors();
        // a bare `spawn Meter` leaves it empty (MAIN_LANE — the synchronous default). Behavior is unchanged
        // — this only asserts the tier is now *visible*, the foundation the threaded execution reads.
        String threaded = """
                requires pontif.events.{Event, StdOut}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                conductor Meter { onTick(e:Tick) -> emit StdOut("tick ")  e }
                spawn Meter over thread
                main ( emit Tick(1)  0 )
                """;
        String bare = threaded.replace("spawn Meter over thread", "spawn Meter");

        var threadedResult = compiler.compile(threaded, "seating.ptf");
        var bareResult = compiler.compile(bare, "seating.ptf");
        assertTrue(threadedResult instanceof PontifCompiler.CompileResult.Compiled, "threaded seat compiles");
        assertTrue(bareResult instanceof PontifCompiler.CompileResult.Compiled, "bare seat compiles");

        var threadedMod = ((PontifCompiler.CompileResult.Compiled) threadedResult).program().module();
        var bareMod = ((PontifCompiler.CompileResult.Compiled) bareResult).program().module();
        assertEquals(java.util.Set.of("Meter"), threadedMod.threadedConductors(),
                "`over thread` seats Meter on the THREAD tier");
        assertTrue(bareMod.threadedConductors().isEmpty(),
                "a bare spawn stays on the MAIN_LANE — no threaded conductors");
    }

    @Test
    void overThread_everyEmitIsHandled_drivenToQuiescence() {
        // Cut 3b: three emits to an `over thread` conductor are each enqueued to its daemon's inbox; the
        // handler's output (a main-lane StdOut) rides back to the main lane, which drains to quiescence and
        // joins the daemon. All three must land — proving the cross-lane round-trip and termination detection.
        Output o = run("""
                requires pontif.events.{Event, StdOut}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                conductor Meter { onTick(e:Tick) -> emit StdOut("tick ")  e }
                spawn Meter over thread
                main ( emit Tick(1)  emit Tick(2)  emit Tick(3)  0 )
                """);
        int ticks = o.out().split("tick ", -1).length - 1;
        assertEquals(3, ticks, "every emit to the threaded conductor is handled before quiescence");
    }

    @Test
    void overThread_twoConductorPipeline_hopsDaemonToDaemonToMain() {
        // The spike's shape (ConductorGraphSpike), now in the language: `app` (own thread) folds a Command and
        // emits a Status; `display` (own thread) folds the Status and emits a main-lane StdOut. So the event
        // hops daemon(app) → daemon(display) → main — every lane boundary is a real inbox handoff, and the
        // whole graph drives to quiescence and joins. If threading or termination were wrong this would hang or
        // drop the line.
        Output o = run("""
                requires pontif.events.{Event, StdOut}
                struct Command(id:Int)
                struct Status(text:String)
                assign trait Command:Event{}
                assign trait Status:Event{}
                conductor App { onCommand(c:Command) -> emit Status("done ")  c }
                conductor Display { onStatus(s:Status) -> emit StdOut(s.text)  s }
                spawn App over thread
                spawn Display over thread
                main ( emit Command(1)  0 )
                """);
        assertTrue(o.out().contains("done "), "the event hopped app → display → main and rendered");
        assertFalse(o.err().contains("dead letter"), "every hop reached its consumer");
    }

    @Test
    void unseatedConductor_handlerIsInert_eventDeadLetters() {
        // Same conductor, but NO `spawn Meter` — the handler must NOT fire, and the event, reaching
        // no consumer, dead-letters (the slice-3 runtime signal). This is what makes activation real.
        Output o = run("""
                requires pontif.events.{Event, StdOut}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                conductor Meter { onTick(e:Tick) -> emit StdOut("tick ")  e }
                main ( emit Tick(1)  0 )
                """);
        assertFalse(o.out().contains("tick "), "an unseated conductor's handler must stay inert");
        assertTrue(o.err().contains("dead letter"), "the unconsumed event dead-letters");
    }

    @Test
    void seatedConductor_handlerFiresEveryEmit() {
        Output o = run("""
                requires pontif.events.{Event, StdOut}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                conductor Meter { onTick(e:Tick) -> emit StdOut("tick ")  e }
                spawn Meter
                main ( emit Tick(1)  emit Tick(2)  emit Tick(3)  0 )
                """);
        int ticks = o.out().split("tick ", -1).length - 1;
        assertEquals(3, ticks, "the handler fires once per emitted event");
    }

    @Test
    void crossConductorEmitCycle_isACompileError() {
        // Cut 3c (gap 1): A consumes Pong and emits Ping; B consumes Ping and emits Pong — a feedback loop
        // A → B → A. Drive-to-quiescence could never terminate on that, so the emit graph is proven acyclic at
        // compile time (the first consumer of EmitInterface). Rejected before it can hang the runtime.
        RunResult r;
        PrintStream oo = System.out, oe = System.err;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream()));
            System.setErr(new PrintStream(new ByteArrayOutputStream()));
            r = runner.run(compiler.compile("""
                    requires pontif.events.{Event}
                    struct Ping(n:Int)
                    struct Pong(n:Int)
                    assign trait Ping:Event{}
                    assign trait Pong:Event{}
                    conductor A { onPong(p:Pong) -> emit Ping(1)  p }
                    conductor B { onPing(p:Ping) -> emit Pong(1)  p }
                    spawn A
                    spawn B
                    main ( emit Ping(1)  0 )
                    """, "cycle.ptf"), Engine.INTERPRETER);
        } finally {
            System.setOut(oo);
            System.setErr(oe);
        }
        assertTrue(r.isError(), "a cross-conductor emit cycle must fail to compile");
        assertTrue(r.text().toLowerCase().contains("cycle"), "the error names the cycle: " + r.text());
    }

    @Test
    void selfEmittingConductor_isACompileError() {
        // A degenerate cycle: a conductor that emits the very type it consumes (A → A). Same hazard, same
        // fail-closed reject — the self-loop is caught by the DFS just like the two-node loop.
        RunResult r;
        PrintStream oo = System.out, oe = System.err;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream()));
            System.setErr(new PrintStream(new ByteArrayOutputStream()));
            r = runner.run(compiler.compile("""
                    requires pontif.events.{Event}
                    struct Tick(n:Int)
                    assign trait Tick:Event{}
                    conductor Loop { onTick(e:Tick) -> emit Tick(1)  e }
                    spawn Loop
                    main ( emit Tick(1)  0 )
                    """, "selfloop.ptf"), Engine.INTERPRETER);
        } finally {
            System.setOut(oo);
            System.setErr(oe);
        }
        assertTrue(r.isError(), "a self-emitting conductor is a cycle and must fail to compile");
        assertTrue(r.text().toLowerCase().contains("cycle"), "the error names the cycle: " + r.text());
    }

    @Test
    void spawnOfUnknownConductor_isACompileError() {
        RunResult r;
        PrintStream oo = System.out, oe = System.err;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream()));
            System.setErr(new PrintStream(new ByteArrayOutputStream()));
            r = runner.run(compiler.compile("spawn Ghost\nmain ( 0 )", "seating.ptf"), Engine.INTERPRETER);
        } finally {
            System.setOut(oo);
            System.setErr(oe);
        }
        assertTrue(r.isError(), "spawning a conductor no module declares must fail");
        assertTrue(r.text().toLowerCase().contains("ghost")
                || r.text().toLowerCase().contains("conductor"), "the error names the missing conductor: " + r.text());
    }
}
