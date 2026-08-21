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
 * Event substrate — the Action reaction leg (docs/events.md). An {@code action
 * NAME(e:EventSort) -> body} is a reaction: when {@code emit MyEvent(...)} fires an event
 * whose type the action's parameter sort matches, the body runs (single-threaded,
 * synchronously, in declaration order). One event type fans out to many actions; an action
 * whose refined filter doesn't match a given instance is a no-op. Reactions here observe via
 * {@code emit StdOut}, so the tests capture stdout (the {@link EventEmitTest} pattern).
 */
class ActionReactionTest {

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
            RunResult r = runner.run(compiler.compile(src, "actions.ptf"), Engine.INTERPRETER);
            return new Output(out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8), r);
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }

    @Test
    void action_firesOnMatchingEvent() {
        Output o = run("""
                requires pontif.events.{Event, StdOut}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                action onTick(e:Tick) -> emit StdOut("hit")  e
                main ( emit Tick(1)  0 )""");
        assertFalse(o.result().isError(), () -> "program errored: " + o.result().text());
        assertEquals("hit", o.out());
    }

    @Test
    void action_withNoNativeSink_doesNotFailClosed() {
        // Tick has no StdOut/StdErr sink — but a declared action IS a consumer, so the
        // emit must not raise "no consumer" (the slice-1b-only behaviour).
        Output o = run("""
                requires pontif.events.{Event, StdOut}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                action onTick(e:Tick) -> emit StdOut("ok")  e
                main ( emit Tick(1)  42 )""");
        assertFalse(o.result().isError(), () -> "an action is a consumer; got " + o.result().text());
        assertEquals("42", o.result().text(), "emit is write-only; main's value is its trailing expr");
        assertEquals("ok", o.out());
    }

    @Test
    void action_refinedFilter_skipsNonMatchingInstance() {
        // The match-filter is the parameter sort: a refined sort fires only on matches.
        Output o = run("""
                requires pontif.events.{Event, StdOut}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                action onPositive(e:[Tick:@.n > 0]) -> emit StdOut("pos")  e
                main ( emit Tick(-1)  0 )""");
        assertFalse(o.result().isError(), () -> "a filtered no-op is not an error: " + o.result().text());
        assertEquals("", o.out(), "Tick(-1) does not satisfy [Tick:@.n > 0] — the action must not fire");
    }

    @Test
    void action_refinedFilter_firesOnMatchingInstance() {
        Output o = run("""
                requires pontif.events.{Event, StdOut}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                action onPositive(e:[Tick:@.n > 0]) -> emit StdOut("pos")  e
                main ( emit Tick(7)  0 )""");
        assertFalse(o.result().isError(), () -> "program errored: " + o.result().text());
        assertEquals("pos", o.out());
    }

    @Test
    void action_fanOut_firesAllMatchingInDeclarationOrder() {
        Output o = run("""
                requires pontif.events.{Event, StdOut}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                action first(e:Tick) -> emit StdOut("1")  e
                action second(e:Tick) -> emit StdOut("2")  e
                main ( emit Tick(0)  0 )""");
        assertFalse(o.result().isError(), () -> "program errored: " + o.result().text());
        assertEquals("12", o.out(), "one event fans out to both actions, in declaration order");
    }

    @Test
    void action_onSupertypeTrait_firesForSubtypeEmit() {
        // Trait-aware routing (docs/reactive-gui.md §1): emit-ing a concrete Click fires both the
        // exact-type action AND the umbrella action on the GuiEvent supertype trait, but NOT the
        // sibling Resize action — which shares no membership with Click and is never iterated.
        Output o = run("""
                requires pontif.events.{Event, StdOut}
                trait GuiEvent{}
                assign trait GuiEvent:Event{}
                struct Click(id:Int)
                assign trait Click:GuiEvent{}
                struct Resize(w:Int)
                assign trait Resize:GuiEvent{}
                action onClick(e:Click) -> emit StdOut("click ")  e
                action onAnyGui(e:GuiEvent) -> emit StdOut("gui ")  e
                action onResize(e:Resize) -> emit StdOut("resize ")  e
                main ( emit Click(1)  0 )""");
        assertFalse(o.result().isError(), () -> "program errored: " + o.result().text());
        assertEquals("click gui ", o.out(),
                "Click fires its own action and the GuiEvent umbrella, most-specific first; Resize never fires");
    }

    @Test
    void action_onlySupertypeConsumer_doesNotFailClosed() {
        // The fail-closed guard is trait-aware: a subtype emit whose ONLY consumer is a supertype
        // action must route, not error as "no consumer".
        Output o = run("""
                requires pontif.events.{Event, StdOut}
                trait GuiEvent{}
                assign trait GuiEvent:Event{}
                struct Click(id:Int)
                assign trait Click:GuiEvent{}
                action onAnyGui(e:GuiEvent) -> emit StdOut("gui")  e
                main ( emit Click(1)  0 )""");
        assertFalse(o.result().isError(),
                () -> "a supertype action is a consumer under trait-aware routing: " + o.result().text());
        assertEquals("gui", o.out());
    }

    @Test
    void action_refinedSupertypeFilter_gatesSubtypeEmit() {
        // A refinement on the umbrella action still gates: only Clicks past the filter fire it.
        String prog = """
                requires pontif.events.{Event, StdOut}
                trait GuiEvent{}
                assign trait GuiEvent:Event{}
                struct Click(id:Int)
                assign trait Click:GuiEvent{}
                action onBigId(e:[Click:@.id > 5]) -> emit StdOut("big")  e
                action onAnyGui(e:GuiEvent) -> emit StdOut("gui")  e
                """;
        Output hit = run(prog + "main ( emit Click(9)  0 )");
        assertFalse(hit.result().isError(), () -> "errored: " + hit.result().text());
        assertEquals("biggui", hit.out(), "Click(9) passes the refinement and the umbrella");
        Output miss = run(prog + "main ( emit Click(1)  0 )");
        assertFalse(miss.result().isError(), () -> "errored: " + miss.result().text());
        assertEquals("gui", miss.out(), "Click(1) fails the refinement; only the umbrella fires");
    }

    @Test
    void emit_withNoSinkAndNoAction_isANoOp() {
        // No fail-closed guard (docs/reactive-gui.md): an event with neither a sink nor any action
        // is a deliberate no-op — fire-and-forget into the void. emit stays write-only, so main's
        // value is its trailing expression.
        Output o = run("""
                requires pontif.events.{Event}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                main ( emit Tick(1)  42 )""");
        assertFalse(o.result().isError(),
                () -> "an unconsumed emit is a no-op, not an error; got " + o.result().text());
        assertEquals("42", o.result().text());
        assertEquals("", o.out());
    }
}
