package sibarum.pontif.ir;

import sibarum.pontif.core.types.StringValue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The native <b>source</b> registry — the inbound counterpart to {@link NativeFunctions}
 * (the {@code StdOut}/{@code StdErr} sinks). A source is the world emitting <i>into</i> the
 * program: the Pontif internals produce its elements, and the program consumes them as a
 * stream (docs/events.md, "Input is an inbound emit"). The first tenant is {@code stdin}.
 *
 * <p>Routing keys on the fully-qualified function name an {@code import} resolves to
 * ({@code pontif.events/stdin}), so the source is import-gated, never a global. A resolved
 * call to the declared builtin {@code stdin} is intercepted in {@link IrInterpreter} and
 * yields a fresh {@link LiveSource} rather than running its (placeholder) body.
 *
 * <p>The far end is held by the OS, so <b>EOF seals the source</b> — the iterator's
 * pull-loop then terminates by construction. Each call builds its reader at <i>call</i>
 * time, so a test's {@code System.setIn} redirection is honoured (mirrors how
 * {@code NativeFunctions} reads {@code System.out} inside its lambda).
 */
public final class NativeSources {

    /** Builds a fresh source on demand (reader constructed at call time, for redirection). */
    @FunctionalInterface
    public interface Factory {
        LiveSource create();
    }

    private static final String EVENTS_MODULE = "pontif.events";

    private static final Map<String, Factory> ENTRIES = new LinkedHashMap<>();

    static {
        ENTRIES.put(EVENTS_MODULE + "/stdin", NativeSources::stdinSource);
    }

    private NativeSources() {}

    /**
     * A fresh source for the resolved function {@code name}, or null if it names no native
     * source. Matches the fully-qualified name and the bare suffix (a builtin decl may carry
     * either form depending on how the link qualified it).
     */
    public static LiveSource get(String name) {
        if (name == null) return null;
        Factory f = ENTRIES.get(name);
        if (f == null) f = ENTRIES.get(EVENTS_MODULE + "/" + name);
        return f == null ? null : f.create();
    }

    /** stdin as a demand-driven line source; {@code readLine() == null} (EOF) seals it. */
    private static LiveSource stdinSource() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        Supplier<Optional<Object>> pull = () -> {
            try {
                String line = reader.readLine();
                return line == null ? Optional.empty() : Optional.of(new StringValue(line));
            } catch (IOException e) {
                return Optional.empty();  // a broken pipe seals the source, like EOF
            }
        };
        return new LiveSource(pull);
    }
}
