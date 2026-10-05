package sibarum.pontif.host;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.runtime.module.OrchestraBridge.Pace;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cadence as the framework's pacing contract: which frames fire a beat, and what the parked loop is told about
 * when it must wake. No window and no interpreter - the frame is a method call and the program is a recorder.
 */
class FrameworkTickHostTest {

    private static final long MILLI = 1_000_000L;

    /** A running program that only remembers the beats it was fired. */
    private static final class Recorder implements NativeCalls.Context {
        final List<RecordValue> beats = new ArrayList<>();

        @Override public void fireEvent(RecordValue event) {
            beats.add(event);
        }

        @Override public boolean satisfies(RecordValue value, String traitName) {
            return false;
        }

        @Override public Object invoke(RecordValue value, String methodName) {
            return null;
        }
    }

    private static Object n(RecordValue beat) {
        return beat.members().get("n");
    }

    @Test
    void fixedFiresAtMostOneBeatPerPeriod_andTellsTheParkedLoopWhenToWake() throws Exception {
        Recorder program = new Recorder();
        FrameworkTickHost.Beats beats = new FrameworkTickHost.Beats(3, Pace.FIXED, 40 * MILLI, program);

        assertEquals(0L, beats.nanosUntilNextFrame(), "before the first beat a frame is wanted now");
        beats.onFrame();
        beats.onFrame();
        beats.onFrame();
        assertEquals(1, program.beats.size(), "frames inside the period fire nothing: a beat is dt after the last");

        long wake = beats.nanosUntilNextFrame();
        assertTrue(wake > 0 && wake <= 40 * MILLI, "the loop parks until the next beat is due, not past it: " + wake);

        Thread.sleep(60);
        beats.onFrame();
        assertEquals(2, program.beats.size());
        Thread.sleep(60);
        beats.onFrame();
        beats.onFrame();
        assertEquals(List.of(1L, 2L, 3L), program.beats.stream().map(FrameworkTickHostTest::n).toList(),
                "three beats were asked for and three were fired, numbered from one");
        assertEquals(Long.MAX_VALUE, beats.nanosUntilNextFrame(), "a finished cadence never wakes the loop");
    }

    @Test
    void aLateFrameOwesNoBurst() throws Exception {
        Recorder program = new Recorder();
        FrameworkTickHost.Beats beats = new FrameworkTickHost.Beats(10, Pace.FIXED, 10 * MILLI, program);

        beats.onFrame();
        Thread.sleep(100);   // ten periods late
        beats.onFrame();
        beats.onFrame();

        assertEquals(2, program.beats.size(), "one beat for the late frame, re-anchored; not ten to catch up");
    }

    @Test
    void eagerAndVsyncWantAFrameAtOnceUntilTheyAreDone() {
        for (Pace pace : List.of(Pace.EAGER, Pace.VSYNC)) {
            Recorder program = new Recorder();
            FrameworkTickHost.Beats beats = new FrameworkTickHost.Beats(2, pace, 0, program);

            assertEquals(0L, beats.nanosUntilNextFrame(), pace + " asks for a frame now");
            beats.onFrame();
            assertEquals(0L, beats.nanosUntilNextFrame(), pace + " still does, with a beat to go");
            beats.onFrame();
            beats.onFrame();
            assertEquals(2, program.beats.size(), pace + ": one beat per frame, and no more than asked for");
            assertEquals(Long.MAX_VALUE, beats.nanosUntilNextFrame());
        }
    }

    @Test
    void retainedBeatsOnlyOnFramesSomethingElseCaused_andNeverWakesTheLoop() {
        Recorder program = new Recorder();
        FrameworkTickHost.Beats beats = new FrameworkTickHost.Beats(5, Pace.RETAINED, 0, program);

        assertEquals(Long.MAX_VALUE, beats.nanosUntilNextFrame(), "no deadline: zero frames when idle");
        beats.onFrame();
        beats.onFrame();
        assertEquals(2, program.beats.size(), "a beat rides each frame that happens anyway");
        assertEquals(Long.MAX_VALUE, beats.nanosUntilNextFrame(), "and it still asks for nothing");
    }
}
