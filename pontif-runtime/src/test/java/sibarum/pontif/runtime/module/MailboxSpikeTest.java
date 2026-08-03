package sibarum.pontif.runtime.module;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The tier-1 mailbox spike (docs/orchestration.md, §"The concurrency model"). Drives {@link MailboxSpike} —
 * display on this (main) thread, application logic on a spawned daemon — and asserts the reactive loop's frames,
 * proving the boundary works with the two threads sharing nothing but the mailboxes. Timeouts guard against a
 * backpressure deadlock (the failure a bounded two-mailbox loop could hide).
 */
class MailboxSpikeTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void displayOnMain_logicOffThread_roundTripsFramesInOrder() {
        List<String> frames = MailboxSpike.run(5);
        assertEquals(List.of("count = 1", "count = 2", "count = 3", "count = 4", "count = 5"), frames);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void boundedMailboxes_backpressureNeitherDeadlocksNorReorders() {
        // 100 presses through capacity-4 mailboxes: the bound must apply backpressure without deadlocking, and
        // per-mailbox ordering must hold, so frame i is exactly "count = i" for the whole run.
        List<String> frames = MailboxSpike.run(100);
        List<String> expected = IntStream.rangeClosed(1, 100).mapToObj(i -> "count = " + i).toList();
        assertEquals(expected, frames);
    }
}
