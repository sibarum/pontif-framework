package sibarum.pontif.net.debug;

import sibarum.elektro.queue.message.Message;

/** A registered {@code action} matched an event and is about to run. */
@Message(id = 2008)
public record ActionFired(String reactionName, String eventType) {}
