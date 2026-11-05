package sibarum.pontif.runtime.module;

import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.IrInterpreter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An in-memory <b>journal</b> of the event substrate — the ordered stream of immutable events a run fires
 * (docs/orchestration.md, §"The concurrency model" → "The journal is the wire format" / supervision). It taps
 * the existing observational {@link IrInterpreter.EventListener} seam, so it adds a recorder without touching
 * the synchronous fold: install it, run, read back the stream.
 *
 * <p>Because every entry is an immutable event record in fire order, this list is byte-for-byte what you would
 * serialize to cross a process or a network — journaling (for crash-restart) and the run-anywhere transport
 * consume the <em>same</em> stream. It also carries the two things supervision needs to make "restart the
 * daemon" safe rather than a double-effect hazard:
 *
 * <ul>
 *   <li>a <b>commit-marker</b> — the seq through which a Player's effects were externally observed. A restart
 *       replays the committed prefix <em>silently</em> (rebuilding state without re-emitting) and only the
 *       {@link #uncommitted() uncommitted} tail live;</li>
 *   <li>a <b>dead-letter</b> list — an event that has crashed its Player {@code N} times is parked here instead
 *       of crash-looping forever (the poison-message bound).</li>
 * </ul>
 *
 * <p>Thread-safe by construction (a {@link CopyOnWriteArrayList} + atomic marker): once placement puts Players
 * on their own threads, several may journal concurrently. This first cut is a single process-wide journal of the
 * whole stream; per-inbox partitioning arrives with real mailboxes in the placement slice.
 */
public final class EventJournal implements IrInterpreter.EventListener {

    /** One journaled event: its monotonic {@code seq} within the run, the immutable event, and where it fired. */
    public record Entry(long seq, RecordValue event, Origin origin) {}

    /** A parked poison message: the event that repeatedly crashed its Player, and why it was given up on. */
    public record DeadLetter(RecordValue event, String reason) {}

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final List<DeadLetter> deadLetters = new CopyOnWriteArrayList<>();
    private final AtomicLong committedThrough = new AtomicLong(-1);   // -1 = nothing committed yet

    @Override
    public void onEmit(RecordValue event, long seq, Origin origin) {
        entries.add(new Entry(seq, event, origin));
    }

    /** The whole stream in fire order — the reified, serializable event log. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** How many events have been journaled. */
    public int size() {
        return entries.size();
    }

    /** The most recent event, or empty if none has fired. */
    public Optional<Entry> latest() {
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(entries.size() - 1));
    }

    /**
     * Mark every event through {@code seq} as externally committed — its effects have been observed, so a
     * restart must rebuild that prefix silently rather than re-emit it. Monotonic: a lower value is ignored.
     */
    public void commitThrough(long seq) {
        committedThrough.accumulateAndGet(seq, Math::max);
    }

    /** The seq through which effects are committed ({@code -1} = nothing yet). */
    public long committedSeq() {
        return committedThrough.get();
    }

    /** The committed prefix — replayed <em>silently</em> on restart (state rebuilt, effects suppressed). */
    public List<Entry> committed() {
        long mark = committedThrough.get();
        List<Entry> prefix = new ArrayList<>();
        for (Entry e : entries) {
            if (e.seq() <= mark) {
                prefix.add(e);
            }
        }
        return List.copyOf(prefix);
    }

    /** The uncommitted tail — the events a restart must reprocess <em>live</em> (their effects may re-fire). */
    public List<Entry> uncommitted() {
        long mark = committedThrough.get();
        List<Entry> tail = new ArrayList<>();
        for (Entry e : entries) {
            if (e.seq() > mark) {
                tail.add(e);
            }
        }
        return List.copyOf(tail);
    }

    /** Park a poison message: it has exhausted its retry bound, so it is given up on rather than crash-looped. */
    public void deadLetter(RecordValue event, String reason) {
        deadLetters.add(new DeadLetter(event, reason));
    }

    /** The parked poison messages. */
    public List<DeadLetter> deadLetters() {
        return List.copyOf(deadLetters);
    }
}
