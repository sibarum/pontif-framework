package sibarum.pontif.runtime.module;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The runtime <b>Conductor</b> — a cooperative main-thread scheduler that seats {@link Player}s, each on a
 * cadence, and drives them on the calling thread until every Player has retired (docs/orchestration.md
 * §Cadence). It is the generalization of the first-cut {@code conduct} loop from one Tick clock to <em>several
 * differently-cadenced Players cohabiting one main thread with no coupling between them</em> — a logic conduit
 * at {@code Fixed(dt)} and a graphics render at {@code Vsync} run side by side here.
 *
 * <p>Ported from the supirvast Conductor spike (dev.supirvast.vastir.preview) and lifted into the runtime so a
 * Pontif conduit and the render host seat on the <em>same</em> scheduler. Cadences arrive already resolved to a
 * period in nanoseconds ({@code 0} = <b>eager</b>: ticks every scheduler pass) — {@link OrchestraBridge}
 * translates a {@code Cadence} value ({@code Fixed(dt)} → {@code dt} ms, {@code Eager} → 0) at the seam.
 *
 * <p>The loop is a <b>deadline-merger</b>: each pass ticks every Player whose deadline has passed, then — if no
 * eager Player paces it — sleeps out the nearest deadline instead of spinning. An eager Player (a vsync-paced
 * render whose {@code tick} blocks on present) is itself the tempo, so the loop never sleeps while one is seated.
 */
public final class Conductor {

    private static final class Seat {
        final Player player;
        final long periodNanos;
        long dueNanos;

        Seat(Player player, long periodNanos, long dueNanos) {
            this.player = player;
            this.periodNanos = periodNanos;
            this.dueNanos = dueNanos;
        }
    }

    private final List<Seat> seats = new ArrayList<>();

    /** Seat a Player with a cadence period in nanoseconds ({@code 0} = eager: ticks every pass). */
    public Conductor seat(Player player, long periodNanos) {
        seats.add(new Seat(player, Math.max(0, periodNanos), System.nanoTime()));
        return this;
    }

    /** True once no Player remains seated — the orchestra has drained. */
    public boolean isEmpty() {
        return seats.isEmpty();
    }

    /** Drive the orchestra on the calling (main) thread until every Player has retired. */
    public void run() {
        while (!seats.isEmpty()) {
            long now = System.nanoTime();
            boolean anyEager = false;
            Iterator<Seat> it = seats.iterator();
            while (it.hasNext()) {
                Seat seat = it.next();
                if (seat.periodNanos == 0) {
                    anyEager = true;
                }
                if (now >= seat.dueNanos) {
                    if (!seat.player.tick(now)) {
                        it.remove();
                    } else {
                        seat.dueNanos = seat.periodNanos == 0 ? now : now + seat.periodNanos;
                    }
                }
            }
            if (!anyEager && !seats.isEmpty()) {
                sleepUntilEarliest();   // nothing paces the loop; wait out the nearest deadline
            }
        }
    }

    private void sleepUntilEarliest() {
        long earliest = Long.MAX_VALUE;
        for (Seat seat : seats) {
            earliest = Math.min(earliest, seat.dueNanos);
        }
        long waitNanos = earliest - System.nanoTime();
        if (waitNanos > 0) {
            try {
                Thread.sleep(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
