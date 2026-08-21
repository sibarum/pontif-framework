package sibarum.pontif.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.PontifParser;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code pontif console} — an interactive REPL over the same compiler the rest
 * of the CLI uses.
 *
 * <p>It keeps a buffer of the <b>declarations</b> entered so far (functions,
 * structs, types, traits, top-level {@code let}s, {@code requires}). Each input
 * line is appended to that buffer and the combined source is parsed:
 * <ul>
 *   <li>a line that produces a trailing <b>expression</b> (a non-trivial
 *       {@code main}) is compiled and run, its value printed — and <i>not</i>
 *       persisted, so it's a one-shot evaluation;</li>
 *   <li>any other line is a <b>declaration</b> — validated by compiling, then
 *       kept in the buffer so later lines can use it.</li>
 * </ul>
 * A line that fails to compile prints its error and leaves the buffer intact
 * (last good state wins). Meta-commands: {@code :list}, {@code :reset},
 * {@code :quit}.
 *
 * <p>{@code --include <dir|.ptfpkg>} sets the directory that {@code requires}
 * lines resolve against, so a session can {@code requires} a loaded module and
 * call into it.
 */
@Command(name = "console",
        mixinStandardHelpOptions = true,
        description = "Interactive REPL: declarations persist, expressions are evaluated and printed.")
public final class Console implements Callable<Integer> {

    @Option(names = {"-I", "--include"}, paramLabel = "<path>",
            description = "A directory or .ptfpkg whose modules `requires` lines resolve against.")
    Path include;

    @Override
    public Integer call() throws Exception {
        Path resolveDir = null;
        Path tempToClean = null;
        if (include != null) {
            if (Files.isDirectory(include)) {
                resolveDir = include;
            } else if (include.getFileName().toString().endsWith(Artifacts.EXTENSION)) {
                tempToClean = Files.createTempDirectory("pontif-console-");
                Artifacts.unpack(include, tempToClean);
                resolveDir = tempToClean;
            } else {
                System.err.println("--include must be a directory or a " + Artifacts.EXTENSION + " artifact.");
                return 2;
            }
        }
        try {
            return loop(resolveDir);
        } finally {
            if (tempToClean != null) Artifacts.deleteRecursively(tempToClean);
        }
    }

    private int loop(Path resolveDir) throws IOException {
        boolean interactive = System.console() != null;
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        StringBuilder buffer = new StringBuilder();

        if (interactive) {
            System.out.println("Pontif console. Declarations persist; a bare expression is evaluated and printed.");
            System.out.println("Commands:  :list   :reset   :quit");
        }

        prompt(interactive);
        String line;
        while ((line = in.readLine()) != null) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                if (trimmed.startsWith(":")) {
                    if (handleMeta(trimmed, buffer)) return 0;   // :quit
                } else {
                    evalLine(line, buffer, resolveDir);
                }
            }
            prompt(interactive);
        }
        if (interactive) System.out.println();   // newline after EOF (Ctrl-D)
        return 0;
    }

    /** Handles a {@code :}-prefixed meta-command; returns true to quit. */
    private boolean handleMeta(String cmd, StringBuilder buffer) {
        switch (cmd) {
            case ":quit", ":q", ":exit" -> { return true; }
            case ":reset" -> {
                buffer.setLength(0);
                System.out.println("Session reset.");
            }
            case ":list" -> System.out.println(buffer.isEmpty() ? "(no declarations yet)" : buffer.toString());
            default -> System.err.println("Unknown command: " + cmd + "  (try :list, :reset, :quit)");
        }
        return false;
    }

    private void evalLine(String line, StringBuilder buffer, Path resolveDir) {
        String candidate = buffer.isEmpty() ? line : buffer + "\n" + line;

        // Decide declaration vs. expression by whether the combined source has a
        // non-trivial trailing main. (The buffer alone always has the trivial
        // `0` main, since expressions are never persisted.)
        boolean isExpression;
        try {
            IrModule parsed = PontifParser.parseModule(candidate, "<console>");
            isExpression = !isTrivialMain(parsed.main());
        } catch (sibarum.pontif.parser.ParseException | RuntimeException parseFailure) {
            System.err.println("Parse error: " + parseFailure.getMessage());
            return;
        }

        CompileResult result = CliSupport.COMPILER.compile(candidate, "<console>", resolveDir);
        if (result instanceof CompileResult.Failed failed) {
            System.err.println(CliSupport.formatError(failed.error()));
            return;   // buffer unchanged — last good state wins
        }

        if (isExpression) {
            RunResult run = CliSupport.RUNNER.runInterpreted(result);
            if (run.isError()) {
                System.err.println(CliSupport.formatError(run));
            } else {
                System.out.println(run.text());
            }
            // one-shot: not persisted
        } else {
            if (!buffer.isEmpty()) buffer.append('\n');
            buffer.append(line);
        }
    }

    /** True if {@code main} is the trivial {@code 0} placeholder (no real entry). */
    private static boolean isTrivialMain(IrExpr main) {
        return main instanceof IrExpr.Lit lit && lit.value() == 0L;
    }

    private static void prompt(boolean interactive) {
        if (interactive) {
            System.out.print("pontif> ");
            System.out.flush();
        }
    }
}
