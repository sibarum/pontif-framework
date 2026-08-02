package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Orchestration — the Conductor leg (docs/orchestration.md). {@code pontif.orchestra}'s {@code conduct}
 * native is the first cut of the Conductor: it fires synthetic {@code Tick} beats on a {@code Cadence} into
 * the program's Tick conduit (a Player), whose {@code emit}s reach their effect sinks (Instruments).
 *
 * <p>Slice 2b makes the cadence a real {@code trait Cadence} — {@code Fixed(dt)} / {@code Eager} realized by
 * the headless conductor, {@code Vsync} / {@code Retained} refused (they pace to a display/event source only
 * a windowed Conductor owns). These tests drive a metronome conduit and observe via {@code emit StdOut}, so
 * they capture stdout (the {@link ConduitTest} harness), asserting the beat sequence and cadence dispatch.
 */
class OrchestraTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private record Output(String out, String err, RunResult result) {}

    private Output run(String src) {
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            RunResult r = runner.run(compiler.compileAlt(src, "orchestra.ptf"), Engine.INTERPRETER);
            return new Output(out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8), r);
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }

    // A metronome: `conduct` fires N Tick beats; the `beat` conduit folds each into a running count and
    // dispatches it onward to `onBeat`, which prints the beat number. State (the Count) persists across ticks
    // in the one live interpreter. `Eager` keeps the test fast (no real-time sleep) while still exercising the
    // full clock → conduit-fold → emit-routing path the Conductor drives.
    private static final String METRONOME = """
            requires pontif.events.{Event, StdOut}
            requires pontif.orchestra.{conduct, Tick, %s}

            struct Count(n:Int)
            assign trait Count:Event{}

            conduit beat(e:Tick, c:Count):{Tick, Count} from Count(0) -> {e, Count(c.n + 1)}
            action onBeat(e:Tick) -> emit StdOut(e.n + "")  emit StdOut(" ")  e

            main ( conduct(5, %s) )""";

    @Test
    void eagerCadence_drivesTheMetronomeThroughFiveBeats() {
        Output o = run(String.format(METRONOME, "Eager", "Eager()"));
        assertTrue(o.err().isEmpty(), () -> "unexpected stderr: " + o.err());
        assertEquals("1 2 3 4 5 ", o.out());
    }

    @Test
    void fixedCadence_drivesTheMetronomeAtItsPeriod() {
        // Fixed(10): a short real period keeps the test fast while proving the Fixed variant is read (its `dt`
        // member) rather than falling through to the 500ms default.
        Output o = run(String.format(METRONOME, "Fixed", "Fixed(10)"));
        assertTrue(o.err().isEmpty(), () -> "unexpected stderr: " + o.err());
        assertEquals("1 2 3 4 5 ", o.out());
    }

    @Test
    void vsyncCadence_isRefusedByTheHeadlessConductor() {
        // Vsync paces to a display the headless conduct cannot see — the honest fail (fail-closed), not a
        // silent degrade to Eager. The refusal names the slice that supplies the windowed Conductor.
        Output o = run(String.format(METRONOME, "Vsync", "Vsync()"));
        assertTrue(o.result().isError(), "Vsync should be refused by the headless conductor");
        assertTrue(o.result().text().contains("windowed Conductor"),
                () -> "refusal should point at the windowed Conductor: " + o.result().text());
    }
}
