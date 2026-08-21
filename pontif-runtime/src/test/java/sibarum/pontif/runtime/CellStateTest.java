package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The {@code Cell[T]} clocked-cell primitive (docs/orchestration.md, §"State is a clocked cell"). A
 * conductor's mutable state is an explicit {@code Cell[T]} field; a write is <b>staged</b> (`apply` /
 * `setNext`) and <b>latches on the clock edge</b> — the handler-transaction boundary — so no pure call
 * stack observes a mutation mid-handler. `current` is the stable snapshot; `next` is the staged value.
 */
class CellStateTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String runOut(String src) {
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            RunResult r = runner.run(compiler.compile(src, "cell.ptf"), Engine.INTERPRETER);
            assertFalse(r.isError(), "program should compile and run: " + r);
            return out.toString(StandardCharsets.UTF_8);
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }

    @Test
    void apply_stagesAndLatchesAcrossEvents() {
        // Each tick applies +1; `.next` shows the staged value; the latch advances `current` at commit.
        String out = runOut("""
                requires pontif.events.{Event, StdOut}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                conductor Counter {
                  n:Cell[Int](0),
                  onTick(e:Tick) -> let this.n.apply([(x:Int) -> x + 1])  emit StdOut("" + this.n.next + " ")  e
                }
                spawn Counter
                main ( emit Tick(1)  emit Tick(1)  emit Tick(1)  0 )
                """);
        assertEquals("1 2 3", out.trim(), "apply threads state across ticks, latched each edge");
    }

    @Test
    void currentIsFrozenMidHandler_whileNextAccumulates() {
        // Within one handler `current` stays the snapshot (0); two applies fold into `next` (+2).
        String out = runOut("""
                requires pontif.events.{Event, StdOut}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                conductor C {
                  n:Cell[Int](0),
                  onTick(e:Tick) -> let this.n.apply([(x:Int) -> x + 1])  let this.n.apply([(x:Int) -> x + 1])  emit StdOut("" + this.n.current + "/" + this.n.next)  e
                }
                spawn C
                main ( emit Tick(1)  0 )
                """);
        assertEquals("0/2", out.trim(), "current frozen at the snapshot; next folds both applies");
    }

    @Test
    void setNextReplaces_resetRestoresInit() {
        String out = runOut("""
                requires pontif.events.{Event, StdOut}
                struct Set(v:Int)
                struct Clear(n:Int)
                assign trait Set:Event{}
                assign trait Clear:Event{}
                conductor Box {
                  v:Cell[Int](7),
                  onSet(e:Set) -> let this.v.setNext(e.v)  emit StdOut("" + this.v.next + " ")  e,
                  onClear(e:Clear) -> let this.v.reset()  emit StdOut("" + this.v.next + " ")  e
                }
                spawn Box
                main ( emit Set(5)  emit Clear(0)  0 )
                """);
        assertEquals("5 7", out.trim(), "setNext replaces; reset restores the seed");
    }
}
