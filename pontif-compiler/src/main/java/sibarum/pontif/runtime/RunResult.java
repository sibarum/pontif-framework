package sibarum.pontif.runtime;

import sibarum.pontif.core.Origin;

import java.util.Optional;

/**
 * What compiling or running a program said: its text, whether that text is an error, and where in the source the
 * error is when that is known. {@link PontifCompiler} fails with one; {@link PontifRunner} (in pontif-runtime)
 * answers with one.
 *
 * <p>It was {@code PontifRunner.RunResult} until the compiler moved to its own module, which cannot see the
 * runner.
 */
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
