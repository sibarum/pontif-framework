package sibarum.pontif.net.debug;

import sibarum.elektro.queue.message.Message;

/** The program finished normally; {@code resultText} is the rendered top-level value. */
@Message(id = 2003)
public record RunCompleted(String resultText) {}
