package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.ir.IrInterpreter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MVCC versioned conductor state, slice 1 (docs/mvcc-state.md). The versioning is deliberately invisible at
 * the language level — own-state reads still hit the live working head, so every existing test is unchanged
 * — so this is a white-box substrate test: run a conductor, then inspect the committed version chain through
 * {@link IrInterpreter#stateAsOf}/{@link IrInterpreter#currentVersion}. Proves the transaction unit (one
 * handler firing = at most one committed version) and snapshot isolation (each version shows the state
 * as-of that transaction, not the latest).
 */
class MvccVersionedStateTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private static String count(RecordValue rv) {
        return rv.members().get("count").toString();
    }

    private static IrInterpreter run(String src) {
        var res = new PontifCompiler().compileAlt(src, "mvcc.ptf");
        assertTrue(res instanceof PontifCompiler.CompileResult.Compiled, "program should compile: " + res);
        var prog = ((PontifCompiler.CompileResult.Compiled) res).program();
        IrInterpreter interp = new IrInterpreter(prog.simplifier());
        interp.eval(prog.module());   // bare spawn ⇒ MAIN_LANE ⇒ synchronous: all commits done when eval returns
        return interp;
    }

    @Test
    void oneVersionPerCommittingTransaction_snapshotReadsSeeHistory() {
        IrInterpreter interp = run("""
                requires pontif.events.{Event}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                conductor Counter { count:Int = 0, onTick(e:Tick) -> this.count = this.count + 1  e }
                spawn Counter
                main ( emit Tick(1)  emit Tick(1)  emit Tick(1)  0 )
                """);

        // Three firings, each of which assigned, committed three versions off the v0 seed.
        assertEquals(3L, interp.currentVersion(), "one version per committing handler-transaction");

        // Snapshot isolation: version V shows the state as-of transaction V, not the latest.
        assertEquals("0", count(interp.stateAsOf("Counter", 0)), "v0 is the seed");
        assertEquals("1", count(interp.stateAsOf("Counter", 1)));
        assertEquals("2", count(interp.stateAsOf("Counter", 2)));
        assertEquals("3", count(interp.stateAsOf("Counter", 3)), "newest committed");
        // A version past the clock clamps to the newest snapshot ≤ it.
        assertEquals("3", count(interp.stateAsOf("Counter", 99)));
    }

    @Test
    void readOnlyHandlerCommitsNoVersion() {
        // A handler that never assigns must not churn versions: the working head stays the very object we
        // seeded (reference-equal), so the transaction commits nothing and the clock never advances.
        IrInterpreter interp = run("""
                requires pontif.events.{Event}
                struct Tick(n:Int)
                assign trait Tick:Event{}
                conductor Watcher { count:Int = 7, onTick(e:Tick) -> e }
                spawn Watcher
                main ( emit Tick(1)  emit Tick(1)  0 )
                """);

        assertEquals(0L, interp.currentVersion(), "a read-only handler commits no new version");
        assertEquals("7", count(interp.stateAsOf("Watcher", 0)), "the seed is still the only version");
    }
}
