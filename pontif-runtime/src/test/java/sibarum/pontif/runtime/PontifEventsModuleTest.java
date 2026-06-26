package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Event substrate slice 1a (docs/events.md): the trait vocabulary lives in the
 * builtin module {@code pontif.events} as markers — {@code Event},
 * {@code EventConduit[E,S,R]}, {@code EventStream[R]} — exactly how {@code Stream}
 * began. Contracts ({@code triggered}/{@code next}) and behavior land in later
 * sub-slices. These tests also dogfood the slice-0 {@code main ( )} entry block
 * through the full compile path.
 */
class PontifEventsModuleTest {

    private final PontifCompiler compiler = new PontifCompiler();

    @Test
    void eventTraits_importable() {
        CompileResult r = compiler.compileAlt("""
                requires pontif.events.{Event, EventConduit, EventStream}
                main ( 0 )""", "e.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "event trait vocabulary should import; got " + r);
    }

    @Test
    void emit_isAStatement_overAnEvent() {
        // `emit` is a statement keyword (slice 1b), not an imported function — an
        // event struct can be emitted. Routing to a conduit is a runtime concern
        // (this only compiles; the builtin StdOut/StdErr conduits run in EventEmitTest).
        CompileResult r = compiler.compileAlt("""
                requires pontif.events.{Event}
                struct Ping(n:Int)
                assign trait Ping:Event{}
                main ( emit Ping(1)  0 )""", "e.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "emit should parse as a statement over an event; got " + r);
    }

    @Test
    void struct_canAssignEventConduit() {
        CompileResult r = compiler.compileAlt("""
                requires pontif.events.{EventConduit}
                struct Counter(total:Int)
                assign trait Counter:EventConduit[Int, Int, Int]{}
                main ( 0 )""", "e.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "a struct should be assignable to the EventConduit marker; got " + r);
    }
}
