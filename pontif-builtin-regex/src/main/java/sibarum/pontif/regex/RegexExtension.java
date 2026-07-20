package sibarum.pontif.regex;

import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.runtime.module.Extension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The regex extension (docs/strings.md, string pattern matching) — the {@code pontif.regex}
 * module. It backs the backtick raw-regex literal ({@code `\d+`}) and its {@code match} arms with
 * {@link java.util.regex}. A <b>pure</b> capability (no side-effects), so it rides the
 * {@link Extension#calls()} channel, not {@link Extension#effects()}, and is auto-discovered via
 * {@code META-INF/services} — dropping this module on the classpath self-registers it.
 *
 * <p>Design (the "middle ground", James 2026-07-12): the backtick literal is <b>syntax</b> — used
 * as a matcher it needs no {@code requires}, lowering to fully-qualified internals that reach this
 * module the way {@code emit StdOut} reaches a builtin. The {@code Regex}/{@code Pattern} names are
 * ordinary module names, imported only when written in a sort position (arg/var/return type). A
 * {@code Regex} is a plain struct holding its raw {@code source}; {@link #TRY_MATCH} compiles it on
 * use and does an <b>anchored full match</b> ({@link Matcher#matches()}), returning the capture
 * groups as an ordered tuple, or {@code NoMatch}.
 */
public final class RegexExtension implements Extension {

    /** The module name. */
    public static final String MODULE = "pontif.regex";

    /** The resolved native-call name the interpreter's regex match arm invokes. */
    public static final String TRY_MATCH = MODULE + "/tryMatch";

    /** The nominal type name of the no-match marker (checked by the interpreter, bare or qualified). */
    public static final String NO_MATCH = "NoMatch";

    @Override
    public String moduleName() {
        return MODULE;
    }

    @Override
    public String pontifSource() {
        return SOURCE;
    }

    @Override
    public Map<String, NativeCalls.NativeCall> calls() {
        // tryMatch is invoked directly (by FQN) from the interpreter's regex match arm, not through
        // user-level dispatch — that is what lets a backtick matcher work with no `requires`.
        return Map.of("tryMatch", (args, ctx) -> tryMatch(args));
    }

    private static final String SOURCE = """
            exports @.{Regex, Pattern, NoMatch}

            # A compiled-on-use regular expression. Holds its RAW source (a backtick literal
            # `\\d+` carries the two chars backslash-d verbatim); the native tryMatch compiles it
            # and anchored-matches a subject String. Written as a named type only under `requires
            # pontif.regex.{Regex}`; the backtick literal itself needs no import.
            struct Regex(source:String)

            # The no-match result of a Pattern — a nullary marker (like Nothing).
            struct NoMatch()

            # The extensible matcher contract (regex is the first tenant; the developer-facing
            # extension surface — user pattern kinds — is a later slice). A Pattern consumes a
            # subject String and yields captures (an ordered aggregate) or NoMatch.
            trait Pattern { tryMatch(subject:String):_ }

            0
            """;

    /**
     * The matcher: {@code args = [Regex, subject]}. Compiles the regex's {@code source}, does an
     * anchored full match against {@code subject}, and returns the capture groups (1..n) as a
     * positional {@code _tuple} of Strings, or a {@code NoMatch} marker. A group that did not
     * participate in the match yields an empty String (slice 1; optional-group refinement later).
     */
    private static Object tryMatch(List<Object> args) {
        if (args.size() != 2
                || !(args.get(0) instanceof RecordValue regex)
                || !(regex.members().get("source") instanceof StringValue src)
                || !(args.get(1) instanceof StringValue subject)) {
            throw new IllegalArgumentException(
                    "regex tryMatch expects (Regex, String); got " + args);
        }
        Pattern compiled = Pattern.compile(src.content());
        Matcher matcher = compiled.matcher(subject.content());
        if (!matcher.matches()) {
            return new RecordValue(NO_MATCH, new LinkedHashMap<>());
        }
        Map<String, Object> groups = new LinkedHashMap<>();
        for (int i = 1; i <= matcher.groupCount(); i++) {
            String g = matcher.group(i);
            groups.put("_" + (i - 1), new StringValue(g == null ? "" : g));
        }
        return new RecordValue("_tuple", groups);
    }
}
