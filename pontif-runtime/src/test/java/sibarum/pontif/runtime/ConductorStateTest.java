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
 * Conductor mutable single-owner state (docs/orchestration.md, §Authoring) — a seated conductor's
 * handlers read ({@code this.count}) and mutate ({@code this.count = …}) the conductor's state,
 * which persists across events. Pontif is otherwise immutable; this is the one place mutation is
 * safe (single-owner: one thread drains the conductor). Interpreter-only (the event substrate has
 * no Truffle backend).
 */
class ConductorStateTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String runOut(String src) {
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            RunResult r = runner.run(compiler.compileAlt(src, "state.ptf"), Engine.INTERPRETER);
            assertFalse(r.isError(), "program should compile and run: " + r);
            return out.toString(StandardCharsets.UTF_8);
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }

    @Test
    void handlerReadsInitialState() {
        String out = runOut("""
                requires pontif.events.{Event, StdOut}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                conductor Meter {
                  count:Int = 5,
                  report(e:Tick) -> emit StdOut("" + this.count)  e
                }
                spawn Meter
                main ( emit Tick(1)  0 )
                """);
        assertEquals("5", out.trim(), "the handler reads the seeded state field");
    }

    @Test
    void mutationPersistsAcrossEvents_theCounter() {
        // The crown-jewel: state threads across emits — 0 → 1 → 2 → 3.
        String out = runOut("""
                requires pontif.events.{Event, StdOut}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                conductor Counter {
                  count:Int = 0,
                  onTick(e:Tick) -> this.count = this.count + 1  emit StdOut("" + this.count + " ")  e
                }
                spawn Counter
                main ( emit Tick(1)  emit Tick(1)  emit Tick(1)  0 )
                """);
        assertEquals("1 2 3", out.trim(), "state persists and increments across events");
    }

    @Test
    void readAfterWriteWithinOneHandler_seesTheNewValue() {
        // `this.n` after `this.n = …` in the SAME handler must observe the write (live cell,
        // not a snapshot bound once at fire time).
        String out = runOut("""
                requires pontif.events.{Event, StdOut}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                conductor Bump {
                  n:Int = 0,
                  onTick(e:Tick) -> this.n = this.n + 10  emit StdOut("" + this.n)  e
                }
                spawn Bump
                main ( emit Tick(1)  0 )
                """);
        assertEquals("10", out.trim(), "the read after the write sees the mutated value");
    }

    @Test
    void severalHandlersShareOneStateField_theMotivatingCase() {
        // The reason conductor state is mutable at all (docs/orchestration.md §Authoring): DISTINCT event
        // types, handled by SEPARATE handlers, all folding into ONE shared field — the GUI toy's
        // mouse+keyboard+dialog-into-one-`doc`. Here `Up` and `Down` handlers mutate the same `count`,
        // so the sequence Up, Up, Down reads 1, 2, 1 — proof the field is shared, not per-handler.
        String out = runOut("""
                requires pontif.events.{Event, StdOut}
                struct Up(n:Int)
                struct Down(n:Int)
                assign trait Up:Event{}
                assign trait Down:Event{}
                conductor Tally {
                  count:Int = 0,
                  onUp(e:Up) -> this.count = this.count + 1  emit StdOut("" + this.count + " ")  e,
                  onDown(e:Down) -> this.count = this.count - 1  emit StdOut("" + this.count + " ")  e
                }
                spawn Tally
                main ( emit Up(1)  emit Up(1)  emit Down(1)  0 )
                """);
        assertEquals("1 2 1", out.trim(), "both handlers mutate the one shared state field");
    }

    @Test
    void selfReturningHandler_carriesStateSortAndRuns() {
        // A `:[this._type]` handler declares an honest Self -> Self contract (docs/orchestration.md,
        // cut 2): the terminal `this` type-checks against the conductor's state sort, and the handler
        // still mutates+persists state across events (the returned self is not yet the commit channel —
        // that is a runtime cut; state commits via the assignment path meanwhile).
        String out = runOut("""
                requires pontif.events.{Event, StdOut}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                conductor Counter {
                  count:Int = 0,
                  bump(e:Tick):[this._type] -> this.count = this.count + 1  emit StdOut("" + this.count + " ")  this
                }
                spawn Counter
                main ( emit Tick(1)  emit Tick(1)  0 )
                """);
        assertEquals("1 2", out.trim(), "the Self-returning handler type-checks and threads state");
    }

    @Test
    void mutationOutsideAConductor_isNotRecognized() {
        // `this.x = …` is a conductor-only capability; in a plain function body it is not the
        // assignment form (the language is immutable there), so the program fails to compile.
        RunResult r;
        PrintStream oo = System.out, oe = System.err;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream()));
            System.setErr(new PrintStream(new ByteArrayOutputStream()));
            r = runner.run(compiler.compileAlt(
                    "function f(x:Int):Int -> this.x = 5  x\nmain ( f(1) )", "state.ptf"),
                    Engine.INTERPRETER);
        } finally {
            System.setOut(oo);
            System.setErr(oe);
        }
        assertTrue(r.isError(), "assignment must not be accepted outside a conductor handler");
    }
}
