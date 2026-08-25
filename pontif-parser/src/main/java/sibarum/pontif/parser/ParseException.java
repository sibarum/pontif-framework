package sibarum.pontif.parser;

import sibarum.pontif.core.Origin;

/**
 * Checked. Thrown by {@link PontifParser} when source text doesn't conform to the grammar.
 * Carries the {@link Origin} of the offending token so callers can highlight it.
 */
public class ParseException extends Exception {

    private final Origin origin;

    public ParseException(String message, Origin origin) {
        super(formatMessage(message, origin));
        this.origin = origin == null ? Origin.NONE : origin;
    }

    public Origin origin() {
        return origin;
    }

    private static String formatMessage(String message, Origin origin) {
        if (origin == null || !origin.isPresent()) {
            return message;
        }
        return "[" + origin + "] " + message;
    }
}
