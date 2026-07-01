package sibarum.pontif.net.debug;

import sibarum.elektro.queue.Conduit;
import sibarum.elektro.queue.dyn.DynCodec;
import sibarum.elektro.queue.dyn.DynValue;
import sibarum.elektro.queue.message.MessageType;
import sibarum.elektro.queue.transport.tcp.ElektroTcp;
import sibarum.elektro.queue.wire.WireBufferWriter;
import sibarum.pontif.ast.record.RecordValue;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.net.PontifDyn;

import java.util.concurrent.TimeUnit;

/**
 * The program-side half of the debug port: a running Pontif program's tap that streams typed
 * telemetry to the editor over TCP loopback (docs/events.md).
 *
 * <p>On {@link #attach} it dials the editor's server conduit, announces itself, and installs itself
 * as the interpreter's {@link IrInterpreter.EventListener}, so every {@code emit} the program fires
 * is mirrored to the editor: {@code StdOut}/{@code StdErr} become {@link StdoutChunk}/
 * {@link StderrChunk}, every other event becomes an {@link EventEmitted} carrying its fields as a
 * {@link DynValue}, and each matched {@code action} becomes an {@link ActionFired}. The tap is
 * purely observational and fail-soft: a broken channel never disturbs the program.
 *
 * <p>Wiring is one line at the program entry point (e.g. {@code GuiLauncher}):
 * {@link #attachFromEnv(String)} attaches only when the editor set {@code PONTIF_DEBUG_PORT}.
 */
public final class DebugSession implements IrInterpreter.EventListener, AutoCloseable {

    /** Environment variable the editor sets to the port its debug server is listening on. */
    public static final String PORT_ENV = "PONTIF_DEBUG_PORT";

    private final Conduit conduit;
    private volatile boolean live = true;

    private DebugSession(Conduit conduit) {
        this.conduit = conduit;
    }

    /**
     * Attaches a session if {@link #PORT_ENV} is set and valid, else returns {@code null} (the
     * program simply runs untapped). Never throws: a debug channel that fails to open must not stop
     * the program from running.
     */
    public static DebugSession attachFromEnv(String source) {
        String port = System.getenv(PORT_ENV);
        if (port == null || port.isBlank()) {
            return null;
        }
        try {
            return attach(Integer.parseInt(port.trim()), source);
        } catch (RuntimeException e) {
            System.err.println("[pontif-debug] could not attach: " + e.getMessage());
            return null;
        }
    }

    /** Dials the editor's debug server on {@code 127.0.0.1:port} and installs the tap. */
    public static DebugSession attach(int port, String source) {
        Conduit conduit = ElektroTcp.client("pontif-debug", "127.0.0.1", port, DebugRegistry.newRegistry());
        try {
            conduit.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            conduit.close();
            throw new IllegalStateException("debug server not reachable on port " + port, e);
        }
        DebugSession session = new DebugSession(conduit);
        session.send(DebugHelloCodec.TYPE, new DebugHello(ProcessHandle.current().pid(), source));
        session.send(RunStartedCodec.TYPE, new RunStarted(source));
        IrInterpreter.installEventListener(session);
        return session;
    }

    @Override
    public void onEmit(RecordValue event, long seq, Origin origin) {
        String bare = bareName(event.typeName());
        if (bare.equals("StdOut")) {
            send(StdoutChunkCodec.TYPE, new StdoutChunk(text(event)));
        } else if (bare.equals("StdErr")) {
            send(StderrChunkCodec.TYPE, new StderrChunk(text(event)));
        } else {
            send(EventEmittedCodec.TYPE, new EventEmitted(seq, event.typeName(), encode(event)));
        }
    }

    @Override
    public void onActionFired(String reactionName, RecordValue event) {
        send(ActionFiredCodec.TYPE, new ActionFired(reactionName, event.typeName()));
    }

    /** Reports normal completion and detaches. */
    public void runCompleted(String resultText) {
        send(RunCompletedCodec.TYPE, new RunCompleted(resultText == null ? "" : resultText));
        close();
    }

    /** Reports a failure (line/col 0 when the origin is unknown) and detaches. */
    public void runFailed(String message, int line, int col) {
        send(RunFailedCodec.TYPE, new RunFailed(message == null ? "" : message, line, col));
        close();
    }

    @Override
    public void close() {
        if (!live) {
            return;
        }
        live = false;
        IrInterpreter.installEventListener(null);
        conduit.close();
    }

    // --- internals --------------------------------------------------------------

    private <T> void send(MessageType<T> type, T message) {
        if (!live) {
            return;
        }
        try {
            conduit.action(type).emit(message);
        } catch (RuntimeException e) {
            // The channel faulted (editor gone). Go quiet rather than perturb the program.
            live = false;
        }
    }

    private static byte[] encode(RecordValue event) {
        DynValue dyn = PontifDyn.toDyn(event);
        WireBufferWriter writer = new WireBufferWriter();
        DynCodec.INSTANCE.encode(dyn, writer);
        return writer.toByteArray();
    }

    private static String text(RecordValue event) {
        Object t = event.members().get("text");
        return t instanceof StringValue s ? s.content() : String.valueOf(t);
    }

    private static String bareName(String typeName) {
        if (typeName == null) {
            return "";
        }
        int slash = typeName.lastIndexOf('/');
        return slash < 0 ? typeName : typeName.substring(slash + 1);
    }
}
