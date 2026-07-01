package sibarum.pontif.net.debug;

import sibarum.elektro.queue.message.Message;

/** A chunk written to standard error (a Pontif {@code emit StdErr(...)}). */
@Message(id = 2006)
public record StderrChunk(String text) {}
