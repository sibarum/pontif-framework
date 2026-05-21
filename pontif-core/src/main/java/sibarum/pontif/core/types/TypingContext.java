package sibarum.pontif.core.types;

public final class TypingContext {

    private static final TypingContext EMPTY = new TypingContext();

    private TypingContext() {}

    public static TypingContext empty() {
        return EMPTY;
    }
}
