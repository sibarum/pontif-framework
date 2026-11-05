package sibarum.pontif.host;

import dev.vexelray.framework.shell.AppInfo;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Topic;
import sibarum.pontif.runtime.CompiledProgram;
import sibarum.pontif.runtime.PontifCompiler;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Pontif program's message graph, in the bus-graph text the Inspector reads (vexelray-inspector
 * {@code docs/bus-graph.md}, version 2): its lanes as parts, what each hears and may send, and each topic's kind.
 */
class PontifBusGraphTest {

    @Test
    void aConductorSeatedOverThreadIsAComponentOnALaneOfItsOwn_besideMainsOnTheLogicLane() {
        String text = new PontifBusGraph(Set.of("Pointer"), 64).text();
        assertEquals("""
                # vexelray bus graph 2
                part component pontif.Pointer lane=Pointer
                part component pontif.main lane=logic
                hears pontif.Pointer#edges topic=pontif.lane.Pointer by=mailbox capacity=64
                hears pontif.Pointer#samples topic=pontif.lane.Pointer.samples by=mailbox capacity=64
                hears pontif.main#edges topic=pontif.lane.main by=mailbox capacity=64
                hears pontif.main#samples topic=pontif.lane.main.samples by=mailbox capacity=64
                sends pontif.Pointer topic=pontif.lane.Pointer
                sends pontif.Pointer topic=pontif.lane.Pointer.samples
                sends pontif.Pointer topic=pontif.lane.main
                sends pontif.Pointer topic=pontif.lane.main.samples
                sends pontif.main topic=pontif.lane.Pointer
                sends pontif.main topic=pontif.lane.Pointer.samples
                sends pontif.main topic=pontif.lane.main
                sends pontif.main topic=pontif.lane.main.samples
                topic pontif.lane.Pointer kind=edge
                topic pontif.lane.Pointer.samples kind=state
                topic pontif.lane.main kind=edge
                topic pontif.lane.main.samples kind=state
                """, text);
    }

    record Shown(String id) {
        static final Topic<Shown> TOPIC = Topic.state(Shown.class, Shown::id);
    }

    @Test
    void anExtensionsViewAndInstrumentsJoinTheLanes() {
        String text = new PontifBusGraph(Set.of(), 64)
                .view("pontif.gui")
                .hears("pontif.gui#Shown", Shown.TOPIC, 64)
                .sendsToLanes("pontif.gui")
                .instrument(Shown.TOPIC)
                .text();
        String shown = Shown.class.getCanonicalName();
        assertTrue(text.contains("part view pontif.gui lane=gui\n"), text);
        assertTrue(text.contains("hears pontif.gui#Shown topic=" + shown + " by=mailbox capacity=64\n"), text);
        assertTrue(text.contains("sends pontif.gui topic=pontif.lane.main\n"), text);
        assertTrue(text.contains("sends pontif.main topic=" + shown + "\n"), "a lane's handler sends the command: " + text);
        assertTrue(text.contains("topic " + shown + " kind=state\n"), text);
    }

    @Test
    void theWiringDeclaresTheProgramsGraph_soTheInspectorAndOttermateCanReadIt() {
        var compiled = new PontifCompiler().compile("""
                requires pontif.events.{Event, StdOut}
                struct Ping(n:Int)
                assign trait Ping:Event{}
                conductor Echo { onPing(p:Ping) -> emit StdOut("pong")  p }
                spawn Echo over thread
                main ( 0 )
                """, "graph.ptf");
        if (compiled instanceof PontifCompiler.CompileResult.Failed f) throw new AssertionError(f.error().text());
        CompiledProgram program = ((PontifCompiler.CompileResult.Compiled) compiled).program();
        String text = new PontifWiring(new AppInfo("pontif-graph-test", "Pontif", 640, 480), program).busGraph();
        assertTrue(text.startsWith(PontifBusGraph.HEADER + "\n"), text);
        assertTrue(text.contains("part component pontif.Echo lane=Echo\n"), text);
        assertTrue(text.contains("part component pontif.main lane=logic\n"), text);
    }
}
