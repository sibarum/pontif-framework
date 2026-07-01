package sibarum.pontif.net.debug;

import sibarum.elektro.queue.message.Message;

/** The program's compile/run has begun. */
@Message(id = 2002)
public record RunStarted(String source) {}
