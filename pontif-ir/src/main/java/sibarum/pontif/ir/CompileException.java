package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;

/**
 * Checked. Thrown by {@link IrCompiler} (and lowering passes that re-enter
 * compilation, like {@code TruffleLowering} and {@code IrInterpreter} when
 * they compile a match-branch's pattern sort) for user-level errors in an
 * already-parsed IR — features the compiler doesn't yet support, sort
 * mismatches, or other semantic issues.
 *
 * <p>Distinguished from {@code RuntimeCheckException} (an unchecked runtime
 * error like a dispatch miss) and from {@link sibarum.pontif.parser.ParseException}
 * (a source-level grammar error).
 */
public class CompileException extends Exception {

    private final Origin origin;

    public CompileException(String message) {
        this(message, Origin.NONE);
    }

    public CompileException(String message, Origin origin) {
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
