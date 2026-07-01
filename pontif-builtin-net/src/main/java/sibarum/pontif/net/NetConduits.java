package sibarum.pontif.net;

import sibarum.elektro.queue.Conduit;
import sibarum.elektro.queue.dyn.DynMessages;
import sibarum.elektro.queue.message.ArrayMessageRegistry;
import sibarum.elektro.queue.message.MessageRegistry;
import sibarum.elektro.queue.transport.local.ElektroLocal;
import sibarum.elektro.queue.transport.tcp.ElektroTcp;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.ir.NativeCalls;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The native implementations behind the {@code pontif.net} module's functions.
 *
 * <p>Each factory returns a {@link NativeCalls.NativeCall} the extension binds by name. The
 * open-a-conduit calls ({@code connect}/{@code listen}/{@code local}/{@code localListen}) differ
 * <em>only</em> in the transport they pick — TCP for across-process/network, the in-VM transport
 * for across-thread — and all return the same {@link NetConduitHandle}. Everything downstream
 * ({@code send}, {@code receive}) is transport-blind: this is where "the same program spans threads,
 * processes, and machines" actually lands.
 */
public final class NetConduits {

    private static final long START_TIMEOUT_SECONDS = 5;

    private NetConduits() {}

    /** {@code connect(host, port)} — dial a TCP peer (across process or network). */
    public static NativeCalls.NativeCall connect() {
        return (args, ctx) -> handle(
                ElektroTcp.client("pontif-net", str(args.get(0)), (int) lng(args.get(1)), registry()));
    }

    /** {@code listen(port)} — accept TCP peers on {@code port}. */
    public static NativeCalls.NativeCall listen() {
        return (args, ctx) -> handle(
                ElektroTcp.server("pontif-net", (int) lng(args.get(0)), registry()));
    }

    /** {@code local(name)} — dial an in-VM peer published at {@code name} (across thread). */
    public static NativeCalls.NativeCall local() {
        return (args, ctx) -> handle(ElektroLocal.client("pontif-net", str(args.get(0)), registry()));
    }

    /** {@code localListen(name)} — publish an in-VM endpoint at {@code name} (across thread). */
    public static NativeCalls.NativeCall localListen() {
        return (args, ctx) -> handle(ElektroLocal.server("pontif-net", str(args.get(0)), registry()));
    }

    /** {@code send(conduit, event)} — emit a Pontif value to every peer; returns Int 0. */
    public static NativeCalls.NativeCall send() {
        return (args, ctx) -> {
            asHandle(args.get(0)).send(args.get(1));
            return 0L;
        };
    }

    /** {@code receive(conduit)} — a demand-driven stream of inbound events. */
    public static NativeCalls.NativeCall receive() {
        return (args, ctx) -> asHandle(args.get(0)).receive();
    }

    // --- helpers ----------------------------------------------------------------

    private static MessageRegistry registry() {
        MessageRegistry registry = new ArrayMessageRegistry();
        DynMessages.registerInto(registry);
        return registry;
    }

    private static NetConduitHandle handle(Conduit conduit) {
        try {
            conduit.start().toCompletableFuture().get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            conduit.close();
            throw new IllegalStateException("pontif.net conduit failed to start: " + e.getMessage(), e);
        }
        return new NetConduitHandle(conduit);
    }

    private static NetConduitHandle asHandle(Object value) {
        if (value instanceof NetConduitHandle h) {
            return h;
        }
        throw new IllegalArgumentException(
                "expected a pontif.net conduit handle, got " + (value == null ? "null" : value.getClass()));
    }

    private static String str(Object value) {
        if (value instanceof StringValue s) {
            return s.content();
        }
        if (value instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("expected a String, got " + value);
    }

    private static long lng(Object value) {
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Integer i) {
            return i.longValue();
        }
        throw new IllegalArgumentException("expected an Int, got " + value);
    }
}
