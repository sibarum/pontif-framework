package sibarum.pontif.net;

import org.junit.jupiter.api.Test;
import sibarum.elektro.queue.Conduit;
import sibarum.elektro.queue.DefaultConduit;
import sibarum.elektro.queue.dyn.DynMessages;
import sibarum.elektro.queue.message.ArrayMessageRegistry;
import sibarum.elektro.queue.message.MessageRegistry;
import sibarum.elektro.queue.transport.local.ElektroLocal;
import sibarum.elektro.queue.transport.tcp.ElektroTcp;
import sibarum.elektro.queue.transport.tcp.TcpTransport;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.ir.LiveSource;
import sibarum.pontif.ir.NativeCalls;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the {@code pontif.net} bridge end to end at the value level: a {@code RecordValue} sent
 * from one conduit is received, decoded, and reconstructed on the other &mdash; and that the code
 * is identical whether the transport is in-VM (across thread) or TCP loopback (across process). The
 * transport-agnosticism is the whole point of the builtin.
 */
class NetConduitsIntegrationTest {

    private static final AtomicInteger ENDPOINTS = new AtomicInteger();

    /** A Pontif-shaped event value: {@code app/Tick(n: 42)}. */
    private static RecordValue tick(long n) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("n", n);
        return new RecordValue("app/Tick", fields);
    }

    private static MessageRegistry registry() {
        MessageRegistry registry = new ArrayMessageRegistry();
        DynMessages.registerInto(registry);
        return registry;
    }

    private static void assertRoundTrips(NetConduitHandle from, NetConduitHandle to) {
        LiveSource inbound = to.receive();
        from.send(tick(42));

        Optional<Object> pulled = inbound.pull();
        assertTrue(pulled.isPresent(), "an event should arrive");
        RecordValue received = assertInstanceOf(RecordValue.class, pulled.get());
        assertEquals("app/Tick", received.typeName());
        assertEquals(42L, received.members().get("n"));
    }

    @Test
    void sendReceiveAcrossThreadsViaLocalTransport() throws Exception {
        String endpoint = "net-test-" + ENDPOINTS.incrementAndGet();
        Conduit sc = ElektroLocal.server("pontif-net", endpoint, registry());
        sc.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
        Conduit cc = ElektroLocal.client("pontif-net", endpoint, registry());
        cc.start().toCompletableFuture().get(5, TimeUnit.SECONDS);

        NetConduitHandle server = new NetConduitHandle(sc);
        NetConduitHandle client = new NetConduitHandle(cc);
        try {
            assertRoundTrips(client, server);
            assertRoundTrips(server, client); // bidirectional over the same handles
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    void sendReceiveAcrossProcessesViaTcpLoopback() throws Exception {
        // Bind an ephemeral loopback TCP server (built directly so we can read the port), then dial
        // it. send/receive below are byte-identical to the local-transport case above.
        TcpTransport transport =
                TcpTransport.listening(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        Conduit serverConduit = new DefaultConduit("pontif-net", transport, registry());
        serverConduit.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
        int port = transport.boundPort();

        NetConduitHandle server = new NetConduitHandle(serverConduit);
        NetConduitHandle client = openTcpClient(port);
        try {
            assertRoundTrips(client, server);
        } finally {
            client.close();
            server.close();
        }
    }

    // --- helpers ----------------------------------------------------------------

    private static NetConduitHandle openTcpClient(int port) {
        Conduit conduit = ElektroTcp.client("pontif-net", "127.0.0.1", port, registry());
        try {
            conduit.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new NetConduitHandle(conduit);
    }
}
