package sibarum.pontif.cli;

import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;
import sibarum.pontif.runtime.PontifRunner.RunResult;

/**
 * Shared compile/run plumbing for the subcommands. One {@link PontifCompiler}
 * and {@link PontifRunner} are immutable and thread-safe, so a single instance
 * each is reused across commands.
 *
 * <p>The CLI always runs through {@link PontifRunner.Engine#INTERPRETER}: it
 * keeps startup cheap and keeps the Truffle backend off the reachable path,
 * which matters for the native image (see the module pom's {@code native}
 * profile).
 */
final class CliSupport {

    static final PontifCompiler COMPILER = new PontifCompiler();
    static final PontifRunner RUNNER = new PontifRunner();

    private CliSupport() {}

    /**
     * Runs an already-produced compile result and reports it: the value to
     * stdout on success, the (origin-tagged) message to stderr on a compile or
     * runtime error. Returns the process exit code — {@code 0} on success,
     * {@code 1} on any Pontif error.
     */
    static int runAndReport(PontifCompiler.CompileResult compiled) {
        RunResult result = RUNNER.runInterpreted(compiled);
        if (result.isError()) {
            System.err.println(formatError(result));
            return 1;
        }
        System.out.println(result.text());
        return 0;
    }

    /**
     * An error {@link RunResult} rendered for the terminal: its message, plus
     * its origin in parentheses — but only when the message doesn't already
     * embed that origin (parse/compile messages do; runtime ones may not).
     */
    static String formatError(RunResult result) {
        String text = result.text();
        return text + result.origin()
                .filter(sibarum.pontif.core.Origin::isPresent)
                .map(Object::toString)
                .filter(o -> !text.contains(o))
                .map(o -> "  (" + o + ")")
                .orElse("");
    }
}
