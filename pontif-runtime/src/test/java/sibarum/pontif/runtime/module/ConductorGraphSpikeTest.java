package sibarum.pontif.runtime.module;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The conductor-graph spike (docs/orchestration.md, §"The conductor graph"). Drives {@link ConductorGraphSpike}
 * — an {@code app} conductor and a {@code display} conductor on their own threads — and asserts that a
 * {@code Command} folded on {@code app} emits a {@code Status} that the static routing table delivers across to
 * {@code display} and folds there, in order. Proves cross-conductor forward routing with each conductor owning
 * its own state, sharing only mailboxes. Timeouts guard a backpressure deadlock.
 */
class ConductorGraphSpikeTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void twoConductors_routeEmitsAcrossThreadsViaTheStaticTable() {
        List<String> rendered = ConductorGraphSpike.run(4);
        assertEquals(List.of("count = 1", "count = 2", "count = 3", "count = 4"), rendered);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void forwardPipeline_preservesOrderUnderLoadAndBackpressure() {
        // 200 commands through capacity-16 inboxes: the Command→Status→display pipeline must neither deadlock
        // nor reorder, so display's log is exactly count = 1 .. count = 200.
        List<String> rendered = ConductorGraphSpike.run(200);
        List<String> expected = IntStream.rangeClosed(1, 200).mapToObj(i -> "count = " + i).toList();
        assertEquals(expected, rendered);
    }
}
