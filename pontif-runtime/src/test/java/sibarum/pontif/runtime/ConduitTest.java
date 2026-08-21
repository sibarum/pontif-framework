package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Event substrate — the Conduit leg (docs/reactive-gui.md). A
 * {@code conduit NAME(e:E, s:S):{E, S} from INIT -> BODY} is a stateful fold (a {@code scan}) over
 * the temporal stream of a type's events, sitting BETWEEN {@code emit} and the actions: each emitted
 * E (or subtype, matched via its ancestry) folds the current state, dispatching a value onward to
 * the matching actions while the new state S' threads to the next event.
 *
 * <p>The dispatched value must be the SAME event type (transform the DATA, not the type) or the
 * {@code Nothing} omission value (drop the event). To produce a DIFFERENT type, the fold body
 * re-emits a new event ({@code emit …}), which routes independently — this keeps the conduit
 * cascade-free. The {@code :S} sugar means "pass the event through unchanged, return just the new
 * state". Naming convention (docs/reactive-gui.md): past-tense names are notifications ("it
 * happened", e.g. {@code Counted}); imperative names are commands ("cause it", e.g. {@code Draw}).
 *
 * <p>These tests observe via {@code emit StdOut}, so they capture stdout (the
 * {@link ActionReactionTest} harness). Int→String uses the {@code n + ""} concat idiom (a String
 * operand makes {@code +} concatenate) — there is no {@code intToStr} builtin.
 */
class ConduitTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private record Output(String out, String err, RunResult result) {}

    private Output run(String src) {
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            RunResult r = runner.run(compiler.compile(src, "conduits.ptf"), Engine.INTERPRETER);
            return new Output(out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8), r);
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }

    @Test
    void conduit_foldsStateAndReEmitsNotification() {
        // The crown-jewel end-to-end. A CounterEvent conduit folds each emitted event (matched via
        // the ancestor trait — Increment/Reset are-a CounterEvent) into a threaded Count, and
        // RE-EMITS a past-tense `Counted` notification (a type-change, per the re-emit rule) that the
        // `show` action prints. The event itself passes through unchanged (`:Count` sugar). State
        // persists across the three emits: 0 → 3 → 7 → 0.
        Output o = run("""
                requires pontif.events.{Event, StdOut}
                trait CounterEvent{}
                assign trait CounterEvent:Event{}
                struct Increment(by:Int)
                struct Reset()
                assign trait Increment:CounterEvent{}
                assign trait Reset:CounterEvent{}
                struct Count(n:Int)
                struct Counted(n:Int)
                conduit fold(e:CounterEvent, c:Count):Count from Count(0) -> (
                  let c2 = match e { [Increment] -> Count(c.n + e.by)  [Reset] -> Count(0)  [_] -> c }
                  emit Counted(c2.n)
                  c2
                )
                action show(x:Counted) -> emit StdOut(x.n + "")  emit StdOut(" ")  x
                main ( emit Increment(3)  emit Increment(4)  emit Reset()  0 )""");
        assertFalse(o.result().isError(), () -> "program errored: " + o.result().text());
        assertEquals("3 7 0 ", o.out(),
                "the conduit threads Count across the three emits and re-emits each as a Counted");
    }

    @Test
    void conduit_transformsEventDataSameType_actionSeesTheTransformedData() {
        // A conduit may rewrite an event's DATA as long as the TYPE is unchanged. `doubler` dispatches
        // a Bump with n doubled; the action sees the transformed values (10, 20), not the originals.
        Output o = run("""
                requires pontif.events.{Event, StdOut}
                struct Bump(n:Int)
                assign trait Bump:Event{}
                struct Count(n:Int)
                conduit doubler(e:Bump, c:Count):{Bump, Count} from Count(0) ->
                  {Bump(e.n * 2), Count(c.n + 1)}
                action onBump(e:Bump) -> emit StdOut(e.n + "")  emit StdOut(" ")  e
                main ( emit Bump(5)  emit Bump(10)  0 )""");
        assertFalse(o.result().isError(), () -> "program errored: " + o.result().text());
        assertEquals("10 20 ", o.out(), "the action sees the conduit-transformed (doubled) event data");
    }

    @Test
    void conduit_passThroughSugar_leavesTheEventUnchanged() {
        // The `:S` sugar passes the event through untouched (it only folds state), so the action sees
        // the ORIGINAL data (5, 10) — the contrast to the transforming conduit above.
        Output o = run("""
                requires pontif.events.{Event, StdOut}
                struct Bump(n:Int)
                assign trait Bump:Event{}
                struct Count(n:Int)
                conduit counter(e:Bump, c:Count):Count from Count(0) -> Count(c.n + 1)
                action onBump(e:Bump) -> emit StdOut(e.n + "")  emit StdOut(" ")  e
                main ( emit Bump(5)  emit Bump(10)  0 )""");
        assertFalse(o.result().isError(), () -> "program errored: " + o.result().text());
        assertEquals("5 10 ", o.out(), "the `:S` sugar dispatches the event unchanged");
    }

    @Test
    void conduit_returningNothing_dropsTheEvent_stateStillThreads() {
        // Nothing in the dispatched slot DROPS the event (no action fires) while the state still
        // threads — the lossy-filter face of scan. `gate` drops even ticks; only 1 and 3 reach onTick.
        Output o = run("""
                requires pontif.events.{Event, StdOut}
                requires pontif.core.{Nothing}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                struct Count(n:Int)
                function even(n:Int):Bool -> n % 2 == 0
                conduit gate(e:Tick, c:Count):{[Tick | Nothing], Count} from Count(0) -> match even(e.n) {
                  [Bool:true]  -> {Nothing(), Count(c.n + 1)}
                  [Bool:false] -> {e, Count(c.n + 1)}
                }
                action onTick(e:Tick) -> emit StdOut(e.n + "")  emit StdOut(" ")  e
                main ( emit Tick(1)  emit Tick(2)  emit Tick(3)  emit Tick(4)  0 )""");
        assertFalse(o.result().isError(), () -> "program errored: " + o.result().text());
        assertEquals("1 3 ", o.out(), "even ticks are dropped by the conduit; odd ticks pass through");
    }

    @Test
    void conduit_returningADifferentEventType_isRejected() {
        // #1: a conduit may not change the event's TYPE. Dispatching an `Other` from a `Tick` conduit
        // is rejected when the fold runs — to change type you must re-emit, not return a foreign type.
        Output o = run("""
                requires pontif.events.{Event}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                struct Other(n:Int)
                struct Count(n:Int)
                conduit bad(e:Tick, c:Count):{Other, Count} from Count(0) -> {Other(e.n), Count(c.n + 1)}
                main ( emit Tick(1)  0 )""");
        assertTrue(o.result().isError(), "a conduit returning a different event type must be rejected");
        assertTrue(o.result().text().contains("same event type"),
                () -> "error should name the same-type rule; got " + o.result().text());
    }

    @Test
    void conduit_passThroughSugarBody_evaluatedExactlyOnce() {
        // The `:S` sugar desugars to {e, BODY} — BODY appears once, so a side-effecting body fires
        // its emit exactly once per event (a double-evaluation would print "xx").
        Output o = run("""
                requires pontif.events.{Event, StdOut}
                struct Ping(n:Int)
                assign trait Ping:Event{}
                struct Count(n:Int)
                conduit tally(e:Ping, s:Count):Count from Count(0) -> emit StdOut("x")  Count(s.n + 1)
                main ( emit Ping(1)  0 )""");
        assertFalse(o.result().isError(), () -> "program errored: " + o.result().text());
        assertEquals("x", o.out(), "the sugar must not double-evaluate the body");
    }

    @Test
    void conduit_duplicateForSameEventType_isACompileError() {
        // A stateful fold is unique per type — two conduits for the same event type is rejected at
        // compile time (the ordered multi-conduit pipeline is a later step).
        Output o = run("""
                requires pontif.events.{Event}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                struct Count(n:Int)
                conduit a(e:Tick, s:Count):Count from Count(0) -> Count(s.n + 1)
                conduit b(e:Tick, s:Count):Count from Count(0) -> Count(s.n + 2)
                main ( 0 )""");
        assertTrue(o.result().isError(), "two conduits for Tick must be a compile error");
        assertTrue(o.result().text().contains("duplicate conduit"),
                () -> "expected a duplicate-conduit diagnostic; got " + o.result().text());
    }

    @Test
    void conduit_ancestrallyOverlappingTypes_isRejectedBeforeAnyEventFlows() {
        // The conductor-graph single-owner rule (docs/orchestration.md): a conduit on a trait AND a
        // conduit on a type that is-a that trait both match an emit of the type — a routing conflict the
        // runtime would otherwise find only on such an emit. It is now proven single-owner up front, at
        // module load (before top-level lets and main), rather than latently.
        Output o = run("""
                requires pontif.events.{Event}
                trait CounterEvent{}
                assign trait CounterEvent:Event{}
                struct Increment(by:Int)
                assign trait Increment:CounterEvent{}
                struct Count(n:Int)
                conduit onAny(e:CounterEvent, s:Count):Count from Count(0) -> Count(s.n + 1)
                conduit onInc(e:Increment, s:Count):Count from Count(0) -> Count(s.n + e.by)
                main ( 0 )""");
        assertTrue(o.result().isError(), "a conduit on a type and its ancestor trait must be a compile error");
        assertTrue(o.result().text().contains("overlap") && o.result().text().contains("single owning conduit"),
                () -> "expected a single-owner overlap diagnostic; got " + o.result().text());
    }

    @Test
    void emit_withNoConduit_reachesActionsDirectly() {
        // Regression: the no-conduit path is unchanged — an emitted event with no matching conduit
        // is dispatched straight to its actions (and native sink), exactly as before.
        Output o = run("""
                requires pontif.events.{Event, StdOut}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                action onTick(e:Tick) -> emit StdOut("hit")  e
                main ( emit Tick(1)  0 )""");
        assertFalse(o.result().isError(), () -> "program errored: " + o.result().text());
        assertEquals("hit", o.out(), "with no conduit, the event reaches the action directly");
    }
}
