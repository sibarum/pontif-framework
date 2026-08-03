package sibarum.pontif.runtime.module;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The <b>conductor-graph</b> spike (docs/orchestration.md, §"The conductor graph — static topology, dynamic
 * resources"). A host-level harness — no Pontif grammar yet — realizing the hive-mind: several <b>conductors</b>
 * (each a worker thread that runs code and owns some conduits), a <b>static routing table</b> mapping every event
 * type to its single owning conductor, and events flowing <em>forward</em> across conductors with no reply, no
 * await.
 *
 * <p>It proves the runtime shape the interpreter will later take on: <b>each conductor owns its conduits and
 * their state on its own thread</b> (the counter lives only on the app conductor; the rendered log only on the
 * display conductor), the <b>routing table decides the owner</b> of every emitted type at construction (a
 * compile-time table in the real thing), and a cross-conductor emit is delivered by <b>enqueue into the owner's
 * inbox</b> and folded on the owner's thread. The only shared objects are the {@link Mailbox}es and the
 * read-only routing table — no data locks anywhere.
 *
 * <p>Scenario: a {@code display} conductor owns {@link Status}; an {@code app} conductor owns {@link Command} and,
 * on each one, folds a counter and <b>emits a {@code Status}</b> — which the router sends across to {@code display}
 * (a cross-conductor hop) to be folded there. All conductors are started (alive and listening) <em>before</em>
 * the first message flows, so "a message reaches a conduit that isn't online yet" cannot happen — the init race
 * is designed out.
 */
public final class ConductorGraphSpike {

    private ConductorGraphSpike() {
    }

    /** The messages that flow on the graph — all immutable. {@link Close} is the drain signal. */
    public sealed interface Msg permits Command, Status, Close {}

    /** Input to the app conductor: the user "did" something, numbered. */
    public record Command(int id) implements Msg {}

    /** The app conductor's output, owned by the display conductor: a rendered line. */
    public record Status(String text) implements Msg {}

    /** Drain signal: a conductor forwards it downstream, then retires. */
    public record Close() implements Msg {}

    /** A conduit's fold: the owning conductor's current {@code state} + an event → the next state + emitted outputs. */
    @FunctionalInterface
    interface Fold {
        Folded apply(Object state, Msg event);
    }

    /** The result of a fold: the threaded next state and any events to emit onward (forward-only). */
    record Folded(Object state, List<Msg> outputs) {}

    /**
     * A conductor — one worker thread with an inbox, the conduit state it alone owns, and a downstream set it
     * forwards {@link Close} to. It drains its inbox serially (single-owner, so its {@code state} needs no lock),
     * folds each event, and routes the fold's outputs through the shared static {@link Router}.
     */
    static final class Conductor {
        final String name;
        final Mailbox<Msg> inbox = new Mailbox<>(16);
        private final Fold fold;
        private final List<Conductor> downstream;
        private Object state;                       // OWNED by this conductor's thread alone — no lock
        private Router router;                       // read-only once started — shared safely
        private Thread thread;

        Conductor(String name, Object init, Fold fold, List<Conductor> downstream) {
            this.name = name;
            this.state = init;
            this.fold = fold;
            this.downstream = downstream;
        }

        void start(Router router) {
            this.router = router;
            this.thread = new Thread(this::run, "pontif-conductor-" + name);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private void run() {
            while (true) {
                Msg msg = inbox.take();
                if (msg instanceof Close) {
                    downstream.forEach(d -> d.inbox.send(new Close()));   // drain forward, then retire
                    return;
                }
                Folded folded = fold.apply(state, msg);
                state = folded.state();
                folded.outputs().forEach(router::emit);                    // forward-only; no reply, no await
            }
        }

        /** Read the final state — call only after {@link #join()} (the thread is done → safe publication). */
        Object state() {
            return state;
        }

        void join() {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted joining conductor " + name, e);
            }
        }
    }

    /**
     * The static routing table: every event type maps to its single owning conductor (built once, read-only).
     * An {@code emit} resolves the owner and enqueues into its inbox; a type with no owner is a statically-known
     * no-op — the same "emit to no consumer is a deliberate no-op" the substrate already has.
     */
    static final class Router {
        private final Map<Class<? extends Msg>, Conductor> owners;

        Router(Map<Class<? extends Msg>, Conductor> owners) {
            this.owners = Map.copyOf(owners);
        }

        void emit(Msg message) {
            Conductor owner = owners.get(message.getClass());
            if (owner != null) {
                owner.inbox.send(message);
            }
        }
    }

    /**
     * Run the two-conductor pipeline for {@code commands} inputs and return the lines the display conductor
     * rendered, in order. Deterministic: line {@code i} is {@code "count = i"}.
     */
    public static List<String> run(int commands) {
        // display owns Status: folds each into an appended (immutable) log. Its state is a List<String>.
        Conductor display = new Conductor("display", List.<String>of(),
                (state, event) -> {
                    @SuppressWarnings("unchecked")
                    List<String> log = (List<String>) state;
                    List<String> next = new ArrayList<>(log);
                    next.add(((Status) event).text());
                    return new Folded(List.copyOf(next), List.of());
                },
                List.of());

        // app owns Command: folds a counter and emits a Status downstream (a cross-conductor hop to display).
        Conductor app = new Conductor("app", 0,
                (state, event) -> {
                    int count = (int) state + 1;
                    return new Folded(count, List.of(new Status("count = " + count)));
                },
                List.of(display));

        Router router = new Router(Map.of(Command.class, app, Status.class, display));

        // All conductors alive and listening BEFORE any message flows — the init race is designed out.
        display.start(router);
        app.start(router);

        for (int i = 1; i <= commands; i++) {
            router.emit(new Command(i));    // routed to app by the static table
        }
        app.inbox.send(new Close());        // drains forward: app → display, after the Status stream

        app.join();
        display.join();

        @SuppressWarnings("unchecked")
        List<String> rendered = (List<String>) display.state();
        return rendered;
    }
}
