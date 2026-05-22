package sibarum.pontif.demo.parser;

import sibarum.pontif.core.Origin;

public class ParseException extends RuntimeException {

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
