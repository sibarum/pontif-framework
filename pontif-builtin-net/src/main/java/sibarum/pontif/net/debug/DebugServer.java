package sibarum.pontif.net.debug;

import sibarum.elektro.queue.Conduit;
import sibarum.elektro.queue.DefaultConduit;
import sibarum.elektro.queue.dyn.DynCodec;
import sibarum.elektro.queue.dyn.DynValue;
import sibarum.elektro.queue.transport.tcp.TcpTransport;
import sibarum.elektro.queue.wire.WireBufferReader;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * The editor-side half of the debug port: a loopback server the editor stands up <em>before</em>
 * it spawns a program, then hands the port to the child via {@link DebugSession#PORT_ENV}.
 *
 * <p>It subscribes to every debug-protocol message and dispatches decoded telemetry to a
 * {@link Listener} the editor supplies. Callbacks run on elektro-Q's receive thread, so a UI
 * listener must marshal onto its own thread. The server binds to the loopback address on an
 * ephemeral port ({@link #port()}), so nothing off the machine can connect.
 */
public final class DebugServer implements AutoCloseable {

    /**
     * Sink for decoded telemetry. All methods default to no-ops so a listener overrides only what
     * it cares about. Invoked on the receive thread.
     */
    public interface Listener {
        default void onHello(long pid, String source) {}

        default void onRunStarted(String source) {}

        default void onStdout(String text) {}

        default void onStderr(String text) {}

        /** A domain event fired, with its fields decoded into a {@link DynValue}. */
        default void onEvent(long seq, String typeName, DynValue payload) {}

        default void onActionFired(String reactionName, String eventType) {}

        default void onRunCompleted(String resultText) {}

        default void onRunFailed(String message, int line, int col) {}
    }

    private final TcpTransport transport;
    private final Conduit conduit;

    private DebugServer(TcpTransport transport, Conduit conduit) {
        this.transport = transport;
        this.conduit = conduit;
    }

    /** Binds a loopback debug server and wires {@code listener}; the port is {@link #port()}. */
    public static DebugServer start(Listener listener) {
        TcpTransport transport =
                TcpTransport.listening(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        Conduit conduit = new DefaultConduit("pontif-debug-editor", transport, DebugRegistry.newRegistry());
        try {
            conduit.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            conduit.close();
            throw new IllegalStateException("could not start debug server", e);
        }
        subscribe(conduit, listener);
        return new DebugServer(transport, conduit);
    }

    /** The ephemeral loopback port the child should dial (pass it as {@link DebugSession#PORT_ENV}). */
    public int port() {
        return transport.boundPort();
    }

    @Override
    public void close() {
        conduit.close();
    }

    private static void subscribe(Conduit conduit, Listener l) {
        conduit.subscribe(DebugHelloCodec.TYPE, (m, ctx) -> l.onHello(m.pid(), m.source()));
        conduit.subscribe(RunStartedCodec.TYPE, (m, ctx) -> l.onRunStarted(m.source()));
        conduit.subscribe(StdoutChunkCodec.TYPE, (m, ctx) -> l.onStdout(m.text()));
        conduit.subscribe(StderrChunkCodec.TYPE, (m, ctx) -> l.onStderr(m.text()));
        conduit.subscribe(ActionFiredCodec.TYPE, (m, ctx) -> l.onActionFired(m.reactionName(), m.eventType()));
        conduit.subscribe(RunCompletedCodec.TYPE, (m, ctx) -> l.onRunCompleted(m.resultText()));
        conduit.subscribe(RunFailedCodec.TYPE, (m, ctx) -> l.onRunFailed(m.message(), m.line(), m.col()));
        conduit.subscribe(EventEmittedCodec.TYPE, (m, ctx) -> {
            DynValue payload = DynCodec.INSTANCE.decode(new WireBufferReader(m.payload()));
            l.onEvent(m.seq(), m.typeName(), payload);
        });
    }
}
