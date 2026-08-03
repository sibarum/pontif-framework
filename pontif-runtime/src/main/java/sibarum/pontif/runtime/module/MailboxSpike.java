package sibarum.pontif.runtime.module;

import java.util.ArrayList;
import java.util.List;

/**
 * The <b>tier-1 mailbox spike</b> (docs/orchestration.md, §"The concurrency model" — the same-process-thread
 * row). A host-level harness, no Pontif grammar yet, in the arrangement the GUI framework is built on:
 * <b>display logic on the calling (main) thread, application logic on a spawned daemon thread</b>, communicating
 * <em>only</em> through two {@link Mailbox}es of immutable messages.
 *
 * <p>It exists to prove the discipline the whole model rests on: <b>the two threads share nothing but the
 * queues.</b> The counter {@code count} lives only on the logic thread; the rendered {@code frames} live only on
 * the display thread. Neither ever touches the other's state — an input event ({@link Press}) flows display →
 * logic, the resulting frame ({@link Render}) flows logic → display, and every message in flight is an immutable
 * record. Swap either Mailbox for a socket and the two halves would be two processes with this code unchanged.
 *
 * <p>The loop is deliberately request/response (one {@code Press} → one {@code Render}) so the harness is
 * deterministic and headless — the display "renders" by collecting the frame text in order, which is exactly the
 * single-threaded display-update guarantee: frames are applied on one thread, in the order they arrive.
 */
public final class MailboxSpike {

    private MailboxSpike() {
    }

    /** An input event, display → logic — the user "pressed", carrying a monotonic id. Immutable. */
    public record Press(int id) implements LogicMsg {}

    /** The logic Player's retire signal — drains cleanly instead of being interrupted. Immutable. */
    public record Close() implements LogicMsg {}

    /** What the logic Player's inbox accepts: a {@link Press} to fold, or {@link Close} to retire. */
    public sealed interface LogicMsg permits Press, Close {}

    /** A frame, logic → display — the immutable update the display applies single-threaded. */
    public record Render(String text) {}

    /**
     * Run the reactive loop for {@code presses} input cycles with the display on <em>this</em> thread and the
     * application logic on a spawned daemon, and return the frames the display rendered, in order. Deterministic:
     * frame {@code i} is always {@code "count = i"}.
     */
    public static List<String> run(int presses) {
        Mailbox<LogicMsg> toLogic = new Mailbox<>(4);     // small bound → real backpressure, never a deadlock
        Mailbox<Render> toDisplay = new Mailbox<>(4);

        Thread logic = new Thread(() -> logicPlayer(toLogic, toDisplay), "pontif-logic");
        logic.setDaemon(true);
        logic.start();

        List<String> frames = new ArrayList<>();           // OWNED by the display thread alone — no lock
        for (int i = 1; i <= presses; i++) {
            toLogic.send(new Press(i));                    // input event: display → logic
            frames.add(toDisplay.take().text());           // apply the resulting frame, on this one thread, in order
        }
        toLogic.send(new Close());
        join(logic);
        return frames;
    }

    /**
     * The application-logic Player: it owns a {@code count}, drains its inbox serially, folds each {@link Press}
     * into the next count, and emits a {@link Render} to the display's inbox. {@code count} is thread-local — the
     * single-owner discipline that lets it stay lock-free — and the only thing this thread shares with the
     * display is the two mailboxes.
     */
    private static void logicPlayer(Mailbox<LogicMsg> inbox, Mailbox<Render> display) {
        int count = 0;                                     // OWNED by the logic thread alone — no lock
        while (true) {
            switch (inbox.take()) {
                case Close ignored -> {
                    return;                                // retire — the orchestra drains this Player
                }
                case Press ignored -> {
                    count++;                               // fold state serially
                    display.send(new Render("count = " + count));   // effect: emit into the display's inbox
                }
            }
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted joining the logic Player", e);
        }
    }
}
