package sibarum.pontif.runtime;

import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.TruffleLowering;
import sibarum.pontif.ir.TruffleProgram;

import java.util.Optional;

/**
 * Executes an already-{@link CompiledProgram compiled} program through one of
 * two backends. Parse and compile work belong to {@link PontifCompiler};
 * this class only runs.
 *
 * <p>Two engines:
 * <ul>
 *   <li>{@link Engine#INTERPRETER} (default) — walks the IR directly.
 *       Cheaper startup; preferred for short scripts.</li>
 *   <li>{@link Engine#TRUFFLE} — lowers the IR to Truffle nodes and runs
 *       a {@link TruffleProgram}. Pays more setup cost; can benefit from
 *       GraalVM specialization for hot programs.</li>
 * </ul>
 * Both engines observe the same dispatch and refinement machinery, so a
 * successfully-running program returns the same value either way.
 *
 * <p>Stateless and thread-safe.
 */
public final class PontifRunner {

    public enum Engine { INTERPRETER, TRUFFLE }

    /** Runs a compiled program. */
    public RunResult run(CompiledProgram program, Engine engine) {
        try {
            Object value = switch (engine) {
                case INTERPRETER -> new IrInterpreter(program.simplifier()).eval(program.module());
                case TRUFFLE -> new TruffleLowering(program.compiler()).lower(program.module()).run();
            };
            return RunResult.success(formatValue(value));
        } catch (RuntimeCheckException rce) {
            return RunResult.error("Runtime error: " + rce.getMessage(), rce.origin());
        } catch (RuntimeException e) {
            // Backstop for raw (non-Pontif) exceptions — name the class so the
            // report is at least identifiable. Pontif-raised errors should be
            // RuntimeCheckException with an origin; a bare Java exception
            // surfacing here is itself a bug worth seeing.
            return RunResult.error("Runtime error (internal " + e.getClass().getSimpleName()
                    + "): " + e.getMessage());
        }
    }

    /**
     * Convenience: short-circuits a {@link PontifCompiler.CompileResult.Failed}
     * into its carried {@link RunResult}, otherwise runs the compiled program.
     */
    public RunResult run(PontifCompiler.CompileResult compileResult, Engine engine) {
        return switch (compileResult) {
            case PontifCompiler.CompileResult.Compiled c -> run(c.program(), engine);
            case PontifCompiler.CompileResult.Failed f -> f.error();
        };
    }

    private static String formatValue(Object value) {
        if (value == null) return "null";
        if (value instanceof java.math.BigDecimal d) {
            return sibarum.pontif.core.Decimals.display(d);
        }
        return value.toString();
    }

    public record RunResult(String text, boolean isError, Optional<Origin> origin) {

        public RunResult {
            if (text == null) {
                throw new IllegalArgumentException("RunResult text must be non-null");
            }
            if (origin == null) {
                origin = Optional.empty();
            }
        }

        public static RunResult success(String text) {
            return new RunResult(text, false, Optional.empty());
        }

        public static RunResult error(String text, Origin origin) {
            return new RunResult(text, true,
                    origin != null && origin.isPresent() ? Optional.of(origin) : Optional.empty());
        }

        public static RunResult error(String text) {
            return new RunResult(text, true, Optional.empty());
        }
    }
}
