package sibarum.pontif.net.debug;

/**
 * Documentation anchor for the Pontif Editor debug port protocol (docs/events.md).
 *
 * <p>The port is <b>telemetry-only</b> in this version &mdash; a running program streams typed
 * messages back to the editor, one way. Bidirectional control (eval, pause, step, breakpoints) is a
 * later slice that would add editor&rarr;program request messages alongside these.
 *
 * <p>The messages are the top-level {@code @Message} records in this package
 * ({@link DebugHello}, {@link RunStarted}, {@link RunCompleted}, {@link RunFailed},
 * {@link StdoutChunk}, {@link StderrChunk}, {@link EventEmitted}, {@link ActionFired}); the
 * elektro-Q codec generator emits reflection-free codecs and a registrar for them at compile time.
 * Their ids are private to this port and never overlap the {@code pontif.net} envelope
 * ({@link sibarum.elektro.queue.dyn.DynMessages#DYN_ID}). Register them all with
 * {@link DebugRegistry#newRegistry()}.
 */
public final class DebugProtocol {
    private DebugProtocol() {}
}
