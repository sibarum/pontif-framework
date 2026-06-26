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
 * Event substrate slice 1b — output IO (docs/events.md). {@code emit StdOut(...)}
 * is a real statement that routes <b>by event type</b> to a write-only native
 * output conduit ({@link sibarum.pontif.ir.NativeFunctions}). These run full
 * programs through the interpreter and capture the process's standard streams —
 * Pontif's first side effect beyond the {@code Decimal} constructor.
 */
class EventEmitTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private record Output(String out, String err, RunResult result) {}

    /** Runs an alt-syntax program with the process streams captured. */
    private Output run(String src) {
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            RunResult r = runner.run(compiler.compileAlt(src, "events.ptf"), Engine.INTERPRETER);
            return new Output(out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8), r);
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }

    @Test
    void emit_printsToStdout() {
        Output o = run("""
                requires pontif.events.{StdOut}
                main ( emit StdOut("hello")  0 )""");
        assertFalse(o.result().isError(), () -> "program errored: " + o.result().text());
        assertEquals("hello", o.out());
    }

    @Test
    void emit_routesByEventType_toStderr() {
        // Routing is by the event's TYPE: a StdErr lands on stderr, leaving stdout empty.
        Output o = run("""
                requires pontif.events.{StdErr}
                main ( emit StdErr("oops")  0 )""");
        assertFalse(o.result().isError(), () -> "program errored: " + o.result().text());
        assertEquals("oops", o.err());
        assertEquals("", o.out());
    }

    @Test
    void emit_sequencesInOrder() {
        Output o = run("""
                requires pontif.events.{StdOut}
                main ( emit StdOut("a")  emit StdOut("b")  0 )""");
        assertFalse(o.result().isError(), () -> "program errored: " + o.result().text());
        assertEquals("ab", o.out());
    }

    @Test
    void userStructNamedStdOut_doesNotHijackTheBuiltinConduit() {
        // Routing keys on the EXACT qualified type. A user's own `struct StdOut` —
        // even one that IS an Event — is a different type (not pontif.events/StdOut),
        // so it must NOT route to the process stdout: it fails closed, printing nothing.
        Output o = run("""
                requires pontif.events.{Event}
                struct StdOut(text:String)
                assign trait StdOut:Event{}
                main ( emit StdOut("leak?")  0 )""");
        assertTrue(o.result().isError(),
                () -> "a non-conduit event must fail closed, not print; got " + o.result().text());
        assertEquals("", o.out(), "the builtin stdout conduit must not be hijacked");
    }

    @Test
    void emit_rejectsANonEvent_atCompileTime() {
        // The Event guard: emitting a struct that does not assign trait Event is a
        // compile error (the master-era `emit(e:Event)` contract, restored).
        Output o = run("""
                struct Ping(n:Int)
                main ( emit Ping(1)  0 )""");
        assertTrue(o.result().isError(), "emitting a non-Event must be rejected");
        assertTrue(o.result().text().contains("Event"),
                () -> "error should mention the Event requirement; got " + o.result().text());
        assertEquals("", o.out());
    }

    @Test
    void emit_isWriteOnly_mainValueIsTheTrailingExpr() {
        // emit yields nothing the program can read; main's value is its trailing expr.
        Output o = run("""
                requires pontif.events.{StdOut}
                main ( emit StdOut("x")  42 )""");
        assertFalse(o.result().isError(), () -> "program errored: " + o.result().text());
        assertEquals("42", o.result().text());
        assertEquals("x", o.out());
    }
}
