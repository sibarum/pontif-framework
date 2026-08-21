package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.RoutingTable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The resolved {@link RoutingTable} (docs/orchestration.md, §"The conductor graph"): a type's routing — its
 * owning conduit + its subscriber actions (the fan-out) — is resolved once and cached, the "routing is a
 * resolved table" invariant. This exercises it directly on a compiled module: one event type owned by a conduit
 * and subscribed by two actions.
 */
class RoutingTableTest {

    private CompiledModule compile(String src) {
        var result = new PontifCompiler().compile(src, "routing.ptf");
        assertNotNull(result);
        if (result instanceof PontifCompiler.CompileResult.Compiled c) {
            return c.program().module();
        }
        throw new AssertionError("program did not compile: " + result);
    }

    @Test
    void routeFor_resolvesOwnerAndSubscribers_andMemoizes() {
        CompiledModule module = compile("""
                requires pontif.events.{Event}
                struct Beep(n:Int)
                assign trait Beep:Event{}
                struct Count(n:Int)
                conduit tally(e:Beep, s:Count):Count from Count(0) -> Count(s.n + 1)
                action onBeepA(e:Beep) -> e
                action onBeepB(e:Beep) -> e
                main ( 0 )""");

        RoutingTable routing = new RoutingTable(module);
        RoutingTable.Route route = routing.routeFor("Beep");

        assertEquals(1, route.conduits().size(), "Beep has a single owning conduit (tally)");
        assertEquals(2, route.subscribers().size(), "Beep fans out to both actions (onBeepA, onBeepB)");
        assertSame(route, routing.routeFor("Beep"), "the resolved route is cached — resolved once, then a lookup");
    }
}
