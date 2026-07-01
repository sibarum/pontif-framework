package sibarum.pontif.net.debug;

import sibarum.elektro.queue.message.Message;

/** A chunk written to standard out (a Pontif {@code emit StdOut(...)}). */
@Message(id = 2005)
public record StdoutChunk(String text) {}
