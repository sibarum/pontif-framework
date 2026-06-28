package sibarum.pontif.ir;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * A demand-driven event source — the runtime side of an infinite stream
 * (docs/events.md, "The three stages"). Pulled one element at a time; the source
 * seals (EOF) by returning {@link Optional#empty()}. This is <b>not</b> a lazy
 * stream <i>value</i> (that locus was wrong — reverted): laziness lives in the
 * <i>iterator</i> that pulls a live source, never in the data. {@code stdin} is the
 * first one — the inbound counterpart to the {@code StdOut} native sink (slice 1b).
 *
 * <p>The {@link IrInterpreter}'s {@code Iterate} engine recognises a live source and
 * drives it with a pull-loop (one element → run the arms, which may {@code emit} →
 * repeat until the source seals), rather than pre-materialising a finite tuple. For
 * {@code stdin} the OS holds the far end, so EOF terminates the loop by construction.
 */
public final class LiveSource {

    private final Supplier<Optional<Object>> puller;

    public LiveSource(Supplier<Optional<Object>> puller) {
        this.puller = puller;
    }

    /** The next element, or empty when the source has sealed (EOF). */
    public Optional<Object> pull() {
        return puller.get();
    }
}
