package sibarum.pontif.runtime.module;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runtime {@link Conductor} — the generic multi-Player scheduler behind the Orchestration API
 * (docs/orchestration.md §Cadence). These drive it directly with host-level counting {@link Player}s (no
 * Pontif program) to prove the mechanism the {@code conduct} native and the graphics render Player both seat
 * on: <em>several differently-cadenced Players cohabiting one main thread, retiring independently</em>.
 */
class ConductorTest {

    /** A Player that ticks {@code beats} times, recording the order it fired in relative to its siblings. */
    private static Player counter(String name, int beats, List<String> log) {
        return new Player() {
            private int fired = 0;

            @Override
            public boolean tick(long nowNanos) {
                if (fired >= beats) {
                    return false;
                }
                fired++;
                log.add(name + fired);
                return fired < beats;
            }
        };
    }

    @Test
    void eagerPlayer_ticksEveryPassUntilItRetires() {
        List<String> log = new ArrayList<>();
        new Conductor().seat(counter("a", 3, log), 0).run();
        assertEquals(List.of("a1", "a2", "a3"), log);
    }

    @Test
    void twoPlayers_shareOneConductorAndBothDrainIt() {
        // A fast eager Player and a slower fixed-period one seated together. The eager one paces the loop; both
        // run to completion and the Conductor drains (empties) only once BOTH have retired — the multi-Player
        // guarantee. We assert on counts + drain, not interleaving order (timing-dependent).
        List<String> log = new ArrayList<>();
        Conductor conductor = new Conductor();
        conductor.seat(counter("fast", 5, log), 0);                 // eager
        conductor.seat(counter("slow", 2, log), 2L * 1_000_000L);   // every 2 ms
        conductor.run();

        assertTrue(conductor.isEmpty(), "the Conductor should drain once every Player retires");
        assertEquals(5, log.stream().filter(s -> s.startsWith("fast")).count());
        assertEquals(2, log.stream().filter(s -> s.startsWith("slow")).count());
    }

    @Test
    void fixedPeriodPlayer_isPacedByItsDeadlineNotSpun() {
        // With no eager Player, the loop sleeps out each deadline rather than busy-spinning. A 3-beat Player at
        // 5 ms should take at least ~10 ms (two inter-beat waits) — proof the deadline-merger actually waits.
        List<String> log = new ArrayList<>();
        long start = System.nanoTime();
        new Conductor().seat(counter("t", 3, log), 5L * 1_000_000L).run();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(3, log.size());
        assertTrue(elapsedMillis >= 9, () -> "expected the 5ms cadence to pace the loop, took " + elapsedMillis + "ms");
    }
}
