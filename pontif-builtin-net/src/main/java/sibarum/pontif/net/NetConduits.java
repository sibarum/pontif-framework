package sibarum.pontif.net;

import sibarum.elektro.queue.Conduit;
import sibarum.elektro.queue.dyn.DynMessages;
import sibarum.elektro.queue.message.ArrayMessageRegistry;
import sibarum.elektro.queue.message.MessageRegistry;
import sibarum.elektro.queue.transport.local.ElektroLocal;
import sibarum.elektro.queue.transport.tcp.ElektroTcp;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.ir.NativeCalls;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The native implementations behind the {@code pontif.net} module's functions.
 *
 * <p>The open-a-conduit calls ({@code connect}/{@code listen}/{@code local}/{@code localListen})
 * differ <em>only</em> in the transport they pick — TCP across process/network, the in-VM transport
 * across threads — and all return a {@code NetConduit(id:Int)} struct (the real handle lives in
 * {@link NetRegistry}, keyed by that id). Everything downstream ({@code send}, {@code receiveN}) is
 * transport-blind: this is where "the same program spans threads, processes, and machines" lands.
 *
 * <p>{@code receiveN} is the bounded, terminating receive — it pulls a fixed number of events into
 * a tuple a program iterates with ordinary finite-stream combinators. Unbounded {@code receive}
 * (a live source) awaits Pontif's infinite-stream work; today it would never seal.
 */
public final class NetConduits {

    /** The fully-qualified name of the conduit-handle struct declared in the {@code pontif.net} module. */
    static final String CONDUIT_TYPE = "pontif.net/NetConduit";

    private static final long START_TIMEOUT_SECONDS = 5;

    private NetConduits() {}

    /** {@code connect(host, port)} — dial a TCP peer (across process or network). */
    public static NativeCalls.NativeCall connect() {
        return (args, ctx) -> open(
                ElektroTcp.client("pontif-net", str(args.get(0)), (int) lng(args.get(1)), registry()));
    }

    /** {@code listen(port)} — accept TCP peers on {@code port}. */
    public static NativeCalls.NativeCall listen() {
        return (args, ctx) -> open(ElektroTcp.server("pontif-net", (int) lng(args.get(0)), registry()));
    }

    /** {@code local(name)} — dial an in-VM peer published at {@code name} (across thread). */
    public static NativeCalls.NativeCall local() {
        return (args, ctx) -> open(ElektroLocal.client("pontif-net", str(args.get(0)), registry()));
    }

    /** {@code localListen(name)} — publish an in-VM endpoint at {@code name} (across thread). */
    public static NativeCalls.NativeCall localListen() {
        return (args, ctx) -> open(ElektroLocal.server("pontif-net", str(args.get(0)), registry()));
    }

    /** {@code send(conduit, event)} — emit a Pontif value to every peer; returns Int 0. */
    public static NativeCalls.NativeCall send() {
        return (args, ctx) -> {
            handle(args.get(0)).send(args.get(1));
            return 0L;
        };
    }

    /** {@code receiveN(conduit, count)} — pull {@code count} inbound events as a finite tuple. */
    public static NativeCalls.NativeCall receiveN() {
        return (args, ctx) -> {
            List<Object> events = handle(args.get(0)).take((int) lng(args.get(1)));
            Map<String, Object> tuple = new LinkedHashMap<>();
            for (int i = 0; i < events.size(); i++) {
                tuple.put("_" + i, events.get(i));
            }
            return new RecordValue("_tuple", tuple);
        };
    }

    // --- helpers ----------------------------------------------------------------

    private static MessageRegistry registry() {
        MessageRegistry registry = new ArrayMessageRegistry();
        DynMessages.registerInto(registry);
        return registry;
    }

    /** Starts {@code conduit}, registers its handle, and returns the {@code NetConduit(id)} value. */
    private static RecordValue open(Conduit conduit) {
        try {
            conduit.start().toCompletableFuture().get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            conduit.close();
            throw new IllegalStateException("pontif.net conduit failed to start: " + e.getMessage(), e);
        }
        long id = NetRegistry.register(new NetConduitHandle(conduit));
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("id", id);
        return new RecordValue(CONDUIT_TYPE, members);
    }

    /** Resolves the live handle behind a {@code NetConduit(id)} value. */
    private static NetConduitHandle handle(Object conduitValue) {
        if (conduitValue instanceof RecordValue rec && rec.members().get("id") instanceof Long id) {
            return NetRegistry.require(id);
        }
        throw new IllegalArgumentException(
                "expected a NetConduit(id) value, got " + (conduitValue == null ? "null" : conduitValue));
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
