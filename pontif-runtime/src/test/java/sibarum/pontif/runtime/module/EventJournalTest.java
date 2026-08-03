package sibarum.pontif.runtime.module;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;
import sibarum.pontif.runtime.PontifRunner.Engine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-memory {@link EventJournal} (docs/orchestration.md, §"The concurrency model"). Two checks: the
 * commit-marker / dead-letter contract as a pure data structure, and that installing the journal on the
 * interpreter's observational seam captures the real {@code emit} stream in fire order (the reified, serializable
 * log that is also the cross-process wire format).
 */
class EventJournalTest {

    private static RecordValue ping(int n) {
        return new RecordValue("demo/Ping", Map.of("n", (long) n));
    }

    @Test
    void commitMarker_splitsTheStreamIntoSilentPrefixAndLiveTail() {
        EventJournal journal = new EventJournal();
        for (int n = 1; n <= 5; n++) {
            journal.onEmit(ping(n), n, Origin.NONE);   // seqs 1..5
        }
        assertEquals(5, journal.size());
        assertEquals(-1, journal.committedSeq());
        assertEquals(5, journal.uncommitted().size(), "nothing committed → the whole stream replays live");

        journal.commitThrough(3);
        assertEquals(3, journal.committedSeq());
        assertEquals(List.of(1L, 2L, 3L), journal.committed().stream().map(EventJournal.Entry::seq).toList());
        assertEquals(List.of(4L, 5L), journal.uncommitted().stream().map(EventJournal.Entry::seq).toList());

        journal.commitThrough(2);   // monotonic — a lower mark is ignored
        assertEquals(3, journal.committedSeq());
    }

    @Test
    void deadLetter_parksAPoisonMessage() {
        EventJournal journal = new EventJournal();
        journal.deadLetter(ping(42), "crashed its Player 3 times");
        assertEquals(1, journal.deadLetters().size());
        assertEquals("crashed its Player 3 times", journal.deadLetters().get(0).reason());
    }

    @Test
    void installedOnTheInterpreter_capturesTheEmitStreamInOrder() {
        EventJournal journal = new EventJournal();
        PrintStream origOut = System.out;
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        IrInterpreter.installEventListener(journal);
        try {
            System.setOut(new PrintStream(sink, true, StandardCharsets.UTF_8));
            PontifCompiler.CompileResult program = new PontifCompiler().compileAlt("""
                    requires pontif.events.{Event, StdOut}
                    struct Ping(n:Int)
                    assign trait Ping:Event{}
                    action onPing(e:Ping) -> emit StdOut(e.n + "")  e
                    main ( emit Ping(1)  emit Ping(2)  emit Ping(3)  0 )""", "journal.ptf");
            new PontifRunner().run(program, Engine.INTERPRETER);
        } finally {
            System.setOut(origOut);
            IrInterpreter.installEventListener(null);   // don't leak the listener into other tests
        }

        // Every emit is journaled — the three Pings and the three StdOut re-emits their actions fired.
        List<EventJournal.Entry> pings = journal.entries().stream()
                .filter(e -> e.event().typeName() != null && e.event().typeName().endsWith("Ping"))
                .toList();
        assertEquals(List.of(1L, 2L, 3L), pings.stream().map(e -> (Long) e.event().members().get("n")).toList(),
                "the Ping events are captured in emit order with their data intact");
        assertTrue(journal.size() >= 6, () -> "expected Pings + StdOut re-emits, got " + journal.size());
        // seqs are strictly increasing across the whole stream.
        long prev = -1;
        for (EventJournal.Entry e : journal.entries()) {
            assertTrue(e.seq() > prev, "seqs must strictly increase");
            prev = e.seq();
        }
    }
}
