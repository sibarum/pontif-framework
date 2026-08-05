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
            RunResult r = runner.run(compiler.compileAlt(src, "seating.ptf"), Engine.INTERPRETER);
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
    void spawnOfUnknownConductor_isACompileError() {
        RunResult r;
        PrintStream oo = System.out, oe = System.err;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream()));
            System.setErr(new PrintStream(new ByteArrayOutputStream()));
            r = runner.run(compiler.compileAlt("spawn Ghost\nmain ( 0 )", "seating.ptf"), Engine.INTERPRETER);
        } finally {
            System.setOut(oo);
            System.setErr(oe);
        }
        assertTrue(r.isError(), "spawning a conductor no module declares must fail");
        assertTrue(r.text().toLowerCase().contains("ghost")
                || r.text().toLowerCase().contains("conductor"), "the error names the missing conductor: " + r.text());
    }
}
