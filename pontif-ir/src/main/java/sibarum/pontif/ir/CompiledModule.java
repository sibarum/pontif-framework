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
        Map<IrSort, Sort> compiledSorts,
        Map<String, Sort> structRegistry,
        List<String> topLevelLets,
        Map<String, List<CompiledAction>> actionsByType) {

    public CompiledModule {
        functions = Map.copyOf(functions);
        // Actions (docs/events.md, the Action reaction leg) keyed by the base name of the
        // event type they match. emit enumerates the bucket for an emitted event's type and
        // fires each reaction whose match-filter the event satisfies. An empty/absent bucket
        // (and no native sink) is the "no consumer" fail-closed case.
        actionsByType = Map.copyOf(actionsByType);
        // Declaration-ordered names of the 0-arg functions that lower
        // top-level lets. Force-evaluated by every engine at program start,
        // before main — a binding's claims are notarized whether or not
        // anything references it (the lazy ruling's loophole, closed).
        topLevelLets = List.copyOf(topLevelLets);
        // IdentityHashMap semantics preserved — sorts are keyed by instance, not
        // by record-equality, so two structurally-equal IrSorts at different
        // origins remain distinct entries. Defensive copy keeps the map opaque.
        compiledSorts = new IdentityHashMap<>(compiledSorts);
        // Nominal struct definitions by name (alias name and struct name), so a
        // by-reference struct sort can be resolved to its shape at check time
        // without inlining. Keyed by name (record-equality), unlike compiledSorts.
        structRegistry = Map.copyOf(structRegistry);
    }

    /** Back-compat: a module with no Actions (the pre-Action-leg shape). */
    public CompiledModule(
            String name, DispatchTable dispatch, Map<FunctionDecl, CompiledFunction> functions,
            IrExpr main, Map<IrSort, Sort> compiledSorts, Map<String, Sort> structRegistry,
            List<String> topLevelLets) {
        this(name, dispatch, functions, main, compiledSorts, structRegistry, topLevelLets,
                Map.of());
    }

    /**
     * The Actions registered for the event type named {@code typeName} (its base name, as
     * the compile loop keyed them), or empty if none. The caller tests each one's
     * {@link CompiledAction#matchSort()} against the emitted event before firing.
     */
    public List<CompiledAction> actionsFor(String typeName) {
        return actionsByType.getOrDefault(typeName, List.of());
    }

    /** Whether any Action is registered for {@code typeName} (regardless of match outcome). */
    public boolean hasActionsFor(String typeName) {
        return actionsByType.containsKey(typeName);
    }

    /**
     * A compiled Action (docs/events.md): the {@code matchSort} an emitted event must
     * satisfy for the {@code reaction} (a 1-param function, the event ⇒ for-effect body)
     * to fire. The reaction's value is discarded.
     */
    public record CompiledAction(Sort matchSort, CompiledFunction reaction) {}

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
