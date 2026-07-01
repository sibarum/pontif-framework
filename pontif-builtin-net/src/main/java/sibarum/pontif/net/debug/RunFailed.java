package sibarum.pontif.net.debug;

import sibarum.elektro.queue.message.Message;

/** The program failed; {@code line}/{@code col} are 0 when the failure carries no origin. */
@Message(id = 2004)
public record RunFailed(String message, int line, int col) {}
