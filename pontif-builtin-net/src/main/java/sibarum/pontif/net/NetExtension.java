package sibarum.pontif.net;

import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.runtime.module.Extension;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code pontif.net} language extension: cross-thread / cross-process / cross-network event
 * conduits, backed by elektro-Q (docs/events.md, the concurrency remainder of the effect model).
 *
 * <p>It realises Pontif's declared-but-empty {@code EventConduit}/{@code EventStream} traits as a
 * concrete transport. A program opens a conduit — {@code connect}/{@code listen} for TCP,
 * {@code local}/{@code localListen} for the in-VM transport — then {@code send}s events and iterates
 * {@code receive}'s stream. The <b>same program code</b> works over every transport; only the opener
 * differs. This is the "runtime-portability facilitator": the effect substrate's emit/react model
 * projected across a real boundary, with elektro-Q's virtual-thread receive and the demand-driven
 * {@code receive} stream providing the async conduit + backpressure the roadmap called for.
 *
 * <p>Values cross the wire through {@link PontifDyn}: a {@code RecordValue} is encoded as elektro-Q's
 * self-describing {@code DynValue} under one envelope id, so an unbounded set of event types shares
 * one conduit without any compile-time schema.
 */
public final class NetExtension implements Extension {

    @Override
    public String moduleName() {
        return "pontif.net";
    }

    @Override
    public Map<String, NativeCalls.NativeCall> calls() {
        Map<String, NativeCalls.NativeCall> calls = new LinkedHashMap<>();
        calls.put("connect", NetConduits.connect());
        calls.put("listen", NetConduits.listen());
        calls.put("local", NetConduits.local());
        calls.put("localListen", NetConduits.localListen());
        calls.put("send", NetConduits.send());
        calls.put("receiveN", NetConduits.receiveN());
        return calls;
    }
}
