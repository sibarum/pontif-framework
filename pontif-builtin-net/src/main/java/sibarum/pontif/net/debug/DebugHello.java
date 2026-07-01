package sibarum.pontif.net.debug;

import sibarum.elektro.queue.message.Message;

/** Sent once on attach: the program identifies itself and the source it is running. */
@Message(id = 2001)
public record DebugHello(long pid, String source) {}
