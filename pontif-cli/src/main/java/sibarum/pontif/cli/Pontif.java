package sibarum.pontif.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * The {@code pontif} command-line tool: build, run, and explore Pontif modules.
 *
 * <p>A thin orchestration layer over the runtime's headless compile/run surface
 * ({@code PontifCompiler} + {@code PontifRunner}); it adds no language
 * machinery. Runs on a stock JVM ({@code java -jar pontif-cli.jar}) and, via the
 * {@code native} Maven profile, as a GraalVM native binary.
 */
@Command(
        name = "pontif",
        mixinStandardHelpOptions = true,
        version = "pontif 1.0-SNAPSHOT",
        header = "Pontif - build tool and runner for the Pontif language.",
        synopsisSubcommandLabel = "<command>",
        subcommands = {
                Run.class,
                Pack.class,
                Console.class,
                New.class,
                Editor.class,
        })
public final class Pontif implements Runnable {

    /** No subcommand given: print usage rather than doing nothing. */
    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Pontif()).execute(args));
    }
}
