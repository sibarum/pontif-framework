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
 * Dead letters — the runtime observability signal for an {@code emit} that engaged nothing
 * (docs/orchestration.md, §"Honest edges"). An emit stays a no-op by design (a consumer MAY
 * subscribe; if none does, the emit costs nothing), but when a fired event reaches no <b>consumer
 * action</b>, no middleware <b>conduit</b>, and no native <b>sink</b>, the runtime logs it to stderr
 * — every time it fires — so the miss is observable rather than invisible. Not a compile-time check
 * and not an error: emit is never rejected.
 *
 * <p>Role model (as ruled): the <em>Action</em> is the consumer, the <em>conduit</em> is middleware
 * a conductor orchestrates, and the native sink is an Instrument. A dead letter is when none engages.
 */
class DeadLetterTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private record Output(String out, String err) {}

    private Output run(String src) {
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            RunResult r = runner.run(compiler.compileAlt(src, "deadletter.ptf"), Engine.INTERPRETER);
            assertFalse(r.isError(), "program should run (emit is never rejected): " + r);
            return new Output(out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }

    private static int deadLetters(String err) {
        int n = 0, i = 0;
        while ((i = err.indexOf("dead letter", i)) >= 0) { n++; i += 11; }
        return n;
    }

    @Test
    void emitWithNoConsumer_logsADeadLetter() {
        Output o = run("""
                struct Ping(n:Int)
                main ( emit Ping(1)  0 )
                """);
        assertEquals(1, deadLetters(o.err()), "an unhandled emit logs exactly one dead letter");
        assertTrue(o.err().contains("Ping"), "the log names the emitted type");
    }

    @Test
    void emitConsumedByAnAction_doesNotDeadLetter() {
        Output o = run("""
                requires pontif.events.{Event, StdOut}
                struct Ping(n:Int)
                assign trait Ping:Event{}
                action log(e:Ping) -> emit StdOut("got")  e
                main ( emit Ping(1)  0 )
                """);
        assertTrue(o.out().contains("got"), "the consumer action ran");
        assertEquals(0, deadLetters(o.err()), "a consumed emit is not a dead letter");
    }

    @Test
    void emitToANativeSink_doesNotDeadLetter() {
        Output o = run("""
                requires pontif.events.{StdOut}
                main ( emit StdOut("hi")  0 )
                """);
        assertTrue(o.out().contains("hi"), "the sink Instrument printed it");
        assertEquals(0, deadLetters(o.err()), "a sink-claimed emit is not a dead letter");
    }

    @Test
    void everyUnhandledEmitLogs_notJustTheFirst() {
        // The key property: it logs EVERY time the emit fires without a consumer, not once.
        Output o = run("""
                struct Ping(n:Int)
                main ( emit Ping(1)  emit Ping(2)  emit Ping(3)  0 )
                """);
        assertEquals(3, deadLetters(o.err()), "three unhandled fires log three dead letters");
    }

    @Test
    void actionExistsButRefinementRejectsThisInstance_isADeadLetter() {
        // A Ping action exists, but only for n > 10; Ping(1) reaches no consumer → dead letter.
        Output o = run("""
                requires pontif.events.{Event, StdOut}
                struct Ping(n:Int)
                assign trait Ping:Event{}
                action big(e:[Ping:@.n > 10]) -> emit StdOut("big")  e
                main ( emit Ping(1)  0 )
                """);
        assertFalse(o.out().contains("big"), "the refined action must not fire for n=1");
        assertEquals(1, deadLetters(o.err()), "an instance no action accepts is a dead letter");
    }
}
