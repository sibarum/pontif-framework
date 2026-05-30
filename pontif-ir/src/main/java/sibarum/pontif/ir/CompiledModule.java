package sibarum.pontif.ir;

import sibarum.pontif.core.symbolic.DispatchTable;
import sibarum.pontif.core.symbolic.FunctionDecl;
import sibarum.pontif.core.types.Sort;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public record CompiledModule(
        String name,
        DispatchTable dispatch,
        Map<FunctionDecl, CompiledFunction> functions,
        IrExpr main,
        Map<IrSort, Sort> compiledSorts) {

    public CompiledModule {
        functions = Map.copyOf(functions);
        // IdentityHashMap semantics preserved — sorts are keyed by instance, not
        // by record-equality, so two structurally-equal IrSorts at different
        // origins remain distinct entries. Defensive copy keeps the map opaque.
        compiledSorts = new IdentityHashMap<>(compiledSorts);
    }

    /**
     * Looks up the pre-compiled {@link Sort} for the given {@link IrSort}.
     * Throws if the sort wasn't seen by the eager compilation pass — that
     * indicates an {@link IrCompiler} bug (every reachable IrSort should be
     * registered during {@link IrCompiler#compile}).
     */
    public Sort sortFor(IrSort sort) {
        Sort compiled = compiledSorts.get(sort);
        if (compiled == null) {
            throw new IllegalStateException(
                    "Sort was not pre-compiled: " + sort
                            + " — IrCompiler should register every reachable IrSort up-front");
        }
        return compiled;
    }

    public record CompiledFunction(
            FunctionDecl decl,
            IrExpr body,
            List<IrParam> params) {}
}
