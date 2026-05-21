package sibarum.pontif.core.symbolic;

import sibarum.pontif.core.Origin;

public class RuntimeCheckException extends RuntimeException {

    private final Origin origin;

    public RuntimeCheckException(String message) {
        super(message);
        this.origin = Origin.NONE;
    }

    public RuntimeCheckException(String message, Origin origin) {
        super(formatMessage(message, origin));
        this.origin = origin == null ? Origin.NONE : origin;
    }

    public RuntimeCheckException(String message, Origin origin, Throwable cause) {
        super(formatMessage(message, origin), cause);
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
