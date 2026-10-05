package sibarum.pontif.host;

import dev.vexelray.framework.api.FrameStage;
import dev.vexelray.framework.core.DeadlineSource;
import dev.vexelray.framework.shell.Shell;
import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.runtime.module.OrchestraBridge;

/**
 * Paces a program's {@code Cadence} on the framework's loop (docs/orchestration.md, "Cadence - a trait").
 *
 * <p>A cadence is the framework's own pacing contract, not a timeline: a frame hook that fires a beat when one is
 * due, and a {@link DeadlineSource} that tells the parked loop when to wake for the next. That is exactly the
 * pair {@code DeadlineSource} was written for - <i>"a component brings its own deadline with it"</i> - so
 * {@code Fixed(dt)} needs no clock of its own and no thread, and {@code Vsync} and {@code Retained}, which the
 * headless conductor had to refuse, are now honest because there is a display and an event source to pace to.
 *
 * <ul>
 *   <li>{@code Fixed(dt)} - a beat at least {@code dt} ms after the last, so a deadline {@code dt} ahead. Never a
 *       burst to catch up: a frame that arrives late fires one beat and re-anchors, because a clock that owes
 *       beats will fire them all at the conductor that was already behind.</li>
 *   <li>{@code Eager} and {@code Vsync} - a beat every frame, and a deadline of now while beats remain. The loop's
 *       own ceiling holds frames to the display's refresh, so under a framework loop the two coincide.</li>
 *   <li>{@code Retained} - a beat on any frame something else caused, and no deadline: zero frames when idle.</li>
 * </ul>
 *
 * <p>The beat is fired from {@code FrameStage.APP} on the main thread, so a conductor seated {@code over thread}
 * receives it as a mailbox send and a main-lane one folds it inline, in the frame. {@code Tick} is a {@code
 * Sample}, so a conductor that falls behind sees the newest beat and never a backlog.
 */
final class FrameworkTickHost implements OrchestraBridge.TickHost {

    private final Shell shell;

    FrameworkTickHost(Shell shell) {
        this.shell = shell;
    }

    @Override
    public void drive(long ticks, OrchestraBridge.Pace pace, long periodNanos, NativeCalls.Context ctx) {
        Beats beats = new Beats(ticks, pace, periodNanos, ctx);
        shell.hooks().add(FrameStage.APP, beats::onFrame);
        shell.deadline(beats);
    }

    /** One {@code conduct} call's schedule: which beat is next, and when. */
    static final class Beats implements DeadlineSource {

        private final long ticks;
        private final OrchestraBridge.Pace pace;
        private final long periodNanos;
        private final NativeCalls.Context ctx;

        private long fired;
        private long startNanos;
        private long nextDue;

        Beats(long ticks, OrchestraBridge.Pace pace, long periodNanos, NativeCalls.Context ctx) {
            this.ticks = ticks;
            this.pace = pace;
            this.periodNanos = periodNanos;
            this.ctx = ctx;
        }

        private boolean done() {
            return fired >= ticks;
        }

        /** The frame hook: fire the next beat if one is due. Main thread only, so no state here is shared. */
        void onFrame() {
            if (done()) return;
            long now = System.nanoTime();
            if (pace == OrchestraBridge.Pace.FIXED && fired > 0 && now < nextDue) return;
            if (fired == 0) startNanos = now;
            fired++;
            // Anchored to now rather than to the previous due time: a late frame owes nothing.
            nextDue = now + periodNanos;
            OrchestraBridge.beat(ctx, fired, (now - startNanos) / 1_000_000L);
        }

        @Override
        public long nanosUntilNextFrame() {
            if (done()) return Long.MAX_VALUE;
            return switch (pace) {
                // Nothing fired yet means the first beat is due now; an unset anchor must not be subtracted from.
                case FIXED -> fired == 0 ? 0L : Math.max(0L, nextDue - System.nanoTime());
                case EAGER, VSYNC -> 0L;
                case RETAINED -> Long.MAX_VALUE;
            };
        }
    }
}
