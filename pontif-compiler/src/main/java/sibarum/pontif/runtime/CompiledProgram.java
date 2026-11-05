package sibarum.pontif.runtime;

import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;

/**
 * A successfully-compiled Pontif source: the {@link CompiledModule} plus the
 * {@link IrCompiler} and {@link Simplifier} that produced it (kept around so
 * the Truffle path can re-use the same compiler instance for lowering).
 * Immutable; safe to share across threads and across multiple runs.
 *
 * @param sourceName     human-readable label used in diagnostics
 */
public record CompiledProgram(
        CompiledModule module,
        IrCompiler compiler,
        Simplifier simplifier,
        String sourceName) {

    public CompiledProgram {
        if (module == null) throw new IllegalArgumentException("module must be non-null");
        if (compiler == null) throw new IllegalArgumentException("compiler must be non-null");
        if (simplifier == null) throw new IllegalArgumentException("simplifier must be non-null");
        if (sourceName == null) throw new IllegalArgumentException("sourceName must be non-null");
    }
}
