package sibarum.pontif.net.debug;

import sibarum.elektro.queue.message.Message;

/**
 * A domain event was fired. {@code seq} is its monotonic index within the run; {@code payload} is
 * the event value encoded with {@link sibarum.elektro.queue.dyn.DynCodec} (a
 * {@link sibarum.elektro.queue.dyn.DynValue.Struct}), so the editor can inspect its fields without
 * knowing the program's types.
 */
@Message(id = 2007)
public record EventEmitted(long seq, String typeName, byte[] payload) {}
