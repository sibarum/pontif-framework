package sibarum.pontif.runtime.module;

import sibarum.pontif.ast.record.RecordValue;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.ir.LiveSource;
import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.ir.NativeFunctions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The builtin <b>IO extension</b> (docs/extensions.md, docs/events.md) — the {@code pontif.events}
 * module's side-effecting parts, expressed through the {@link Extension} API rather than hardcoded
 * in the registries. It is the first extension and is installed by default (no external
 * dependency), so every path keeps IO.
 *
 * <ul>
 *   <li>{@link #effects()}: {@code StdOut}/{@code StdErr} — write their {@code text} field to the
 *       process streams. {@code System.out}/{@code System.err} are read <em>at call time</em>, so
 *       test redirection via {@code System.setOut} is honoured.</li>
 *   <li>{@link #calls()}: {@code stdin} — yields a fresh demand-driven {@link LiveSource} reading
 *       {@code System.in} line by line; EOF seals it (the iterator's pull-loop then terminates).
 *       The reader is built at call time, so {@code System.setIn} redirection is honoured.</li>
 * </ul>
 */
public final class IoExtension implements Extension {

    /** The always-installed singleton. */
    public static final IoExtension INSTANCE = new IoExtension();

    private IoExtension() {}

    @Override
    public String moduleName() {
        return BuiltinModules.PONTIF_EVENTS;
    }

    @Override
    public String pontifSource() {
        return SOURCE;
    }

    @Override
    public Map<String, NativeFunctions.Effect> effects() {
        return Map.of(
                "StdOut", (event, origin) -> writeText(System.out, event, origin),
                "StdErr", (event, origin) -> writeText(System.err, event, origin));
    }

    @Override
    public Map<String, NativeCalls.NativeCall> calls() {
        // stdin ignores its (empty) arg list and yields a fresh live source per call.
        return Map.of("stdin", args -> stdinSource());
    }

    private static final String SOURCE = """
            requires pontif.core.{Stream}
            exports @.{Event, EventConduit, EventStream, StdOut, StdErr, stdin}

            trait Event{}

            trait EventConduit[type E, type S, type R]{}

            trait EventStream[type R]{}

            struct StdOut(text:String)
            struct StdErr(text:String)
            assign trait StdOut:Event{}
            assign trait StdErr:Event{}

            # The first inbound source (docs/events.md, "Input is an inbound emit"):
            # the Pontif internals produce stdin's lines. The body is a placeholder —
            # a resolved `stdin()` call runs this extension's Java object (IoExtension.calls)
            # instead, yielding a live demand-driven source pulled lazily by the iterator.
            function stdin():Stream[String] -> {}

            0
            """;

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

    private static void writeText(java.io.PrintStream out, RecordValue event, Origin origin) {
        Object text = event.members().get("text");
        if (!(text instanceof StringValue s)) {
            throw new RuntimeCheckException(
                    "Output event '" + event.typeName() + "' must carry a String 'text' field; got "
                            + (text == null ? "no 'text' field" : text.getClass().getSimpleName()),
                    origin);
        }
        out.print(s.content());
    }
}
