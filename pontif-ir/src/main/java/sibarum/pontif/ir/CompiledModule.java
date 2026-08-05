package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;
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
        Map<String, List<CompiledAction>> actionsByType,
        java.util.Set<String> algebraicFunctions,
        Map<Origin.Span, IrSort> effectiveSorts,
        Map<String, CompiledConduit> conduitsByType,
        Map<String, List<CompiledIndex>> indexesByType,
        Map<String, CompiledConductor> conductors) {

    public CompiledModule {
        functions = Map.copyOf(functions);
        conductors = Map.copyOf(conductors);
        // Names of functions carrying an `assign proof f:Algebraic` claim (docs/algebra).
        // The interpreter tags a metareference `$f[…]` as the concrete nominal
        // `AlgebraicDispatch` (else `DispatchBase`) from this set, so its `.ast` attribute
        // resolves — the runtime image of NarrowingInference's sort stamp.
        algebraicFunctions = java.util.Set.copyOf(algebraicFunctions);
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
        // The effective-sort lens (docs/type-records.md, the Inferred record): span → the accumulated
        // effective sort at that position, produced by EffectiveSortLens before the construction gate
        // and carried here so it survives compilation (read by the gates and, later, an IDE).
        effectiveSorts = Map.copyOf(effectiveSorts);
        // Conduits (docs/reactive-gui.md, the stateful-fold leg) keyed by the base name of
        // the event type they fold. Sits BETWEEN emit and the actions: emit routes an event
        // through the matching conduit (a scan over the type's temporal event stream), then
        // dispatches the conduit's output to the actions. Threaded exactly like actionsByType.
        conduitsByType = Map.copyOf(conduitsByType);
        // Standing index declarations (docs/stream-queries.md §3, docs/keyed.md) by the bare
        // name of the element type they key. Populated from `#index#`-keyed FunctionDecls (the
        // parser's lowering, mirroring #action#/#conduit#). A list per type — an index is a
        // NAMED key-mapping decoupled from T's identity, so several views over one T coexist.
        // Slice B: declared + type-checked, drives no structure; Slice C's pushdown reads it.
        indexesByType = Map.copyOf(indexesByType);
    }

    /** Back-compat constructor for callers predating the index registry (Slice B). */
    public CompiledModule(String name, DispatchTable dispatch,
            Map<FunctionDecl, CompiledFunction> functions, IrExpr main,
            Map<IrSort, Sort> compiledSorts, Map<String, Sort> structRegistry,
            List<String> topLevelLets, Map<String, List<CompiledAction>> actionsByType,
            java.util.Set<String> algebraicFunctions, Map<Origin.Span, IrSort> effectiveSorts,
            Map<String, CompiledConduit> conduitsByType) {
        this(name, dispatch, functions, main, compiledSorts, structRegistry, topLevelLets,
                actionsByType, algebraicFunctions, effectiveSorts, conduitsByType, Map.of(), Map.of());
    }

    /** Back-compat: a module with no conduits (the pre-conduit shape). */
    public CompiledModule(
            String name, DispatchTable dispatch, Map<FunctionDecl, CompiledFunction> functions,
            IrExpr main, Map<IrSort, Sort> compiledSorts, Map<String, Sort> structRegistry,
            List<String> topLevelLets, Map<String, List<CompiledAction>> actionsByType,
            java.util.Set<String> algebraicFunctions, Map<Origin.Span, IrSort> effectiveSorts) {
        this(name, dispatch, functions, main, compiledSorts, structRegistry, topLevelLets,
                actionsByType, algebraicFunctions, effectiveSorts, Map.of());
    }

    /** Back-compat: a module with no effective-sort lens (empty). */
    public CompiledModule(
            String name, DispatchTable dispatch, Map<FunctionDecl, CompiledFunction> functions,
            IrExpr main, Map<IrSort, Sort> compiledSorts, Map<String, Sort> structRegistry,
            List<String> topLevelLets, Map<String, List<CompiledAction>> actionsByType,
            java.util.Set<String> algebraicFunctions) {
        this(name, dispatch, functions, main, compiledSorts, structRegistry, topLevelLets,
                actionsByType, algebraicFunctions, Map.of(), Map.of());
    }

    /** Back-compat: a module with no Actions (the pre-Action-leg shape). */
    public CompiledModule(
            String name, DispatchTable dispatch, Map<FunctionDecl, CompiledFunction> functions,
            IrExpr main, Map<IrSort, Sort> compiledSorts, Map<String, Sort> structRegistry,
            List<String> topLevelLets) {
        this(name, dispatch, functions, main, compiledSorts, structRegistry, topLevelLets,
                Map.of(), java.util.Set.of());
    }

    /** Back-compat: a module with Actions but no algebraic-function set. */
    public CompiledModule(
            String name, DispatchTable dispatch, Map<FunctionDecl, CompiledFunction> functions,
            IrExpr main, Map<IrSort, Sort> compiledSorts, Map<String, Sort> structRegistry,
            List<String> topLevelLets, Map<String, List<CompiledAction>> actionsByType) {
        this(name, dispatch, functions, main, compiledSorts, structRegistry, topLevelLets,
                actionsByType, java.util.Set.of());
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
     * Trait-aware event routing (docs/reactive-gui.md §1): the Actions an emitted value of
     * {@code typeName} may fire — its own type bucket, PLUS every bucket keyed by a trait the
     * type satisfies (transitively, via the {@link sibarum.pontif.core.symbolic.TraitRegistry}
     * base-chain). So an {@code action onAnyGui(e:GuiEvent)} fires on an {@code emit Click(...)}
     * when {@code Click} is-a {@code GuiEvent}, while an {@code action onResize(e:Resize)} is
     * never even iterated. Order is most-specific first (the exact-type bucket, then the trait
     * buckets); each returned Action's {@code matchSort} refinement is still tested by the caller
     * before firing. Cost is O(distinct action buckets) membership tests per emit — bounded by
     * the type hierarchy, not the total Action count.
     */
    public List<CompiledAction> actionsMatching(String typeName) {
        int slash = typeName == null ? -1 : typeName.lastIndexOf('/');
        String bare = slash < 0 ? typeName : typeName.substring(slash + 1);
        List<CompiledAction> out = new java.util.ArrayList<>(actionsFor(typeName));
        if (bare != null && !bare.equals(typeName)) out.addAll(actionsFor(bare));
        sibarum.pontif.core.symbolic.TraitRegistry tr = dispatch.traitRegistry();
        for (String key : actionsByType.keySet()) {
            if (key.equals(typeName) || key.equals(bare)) continue;   // exact buckets already added
            if (tr.satisfiesBareTrait(key, typeName)) out.addAll(actionsFor(key));
        }
        return out;
    }

    /**
     * A compiled Action (docs/events.md): the {@code matchSort} an emitted event must
     * satisfy for the {@code reaction} (a 1-param function, the event ⇒ for-effect body)
     * to fire. The reaction's value is discarded.
     */
    public record CompiledAction(Sort matchSort, CompiledFunction reaction, String conductorName) {
        /** A plain (non-conductor) action — no owning conductor, so no state context. */
        public CompiledAction(Sort matchSort, CompiledFunction reaction) {
            this(matchSort, reaction, null);
        }
    }

    /**
     * A compiled conductor (docs/orchestration.md, §Authoring) — carried so the interpreter can
     * seed its mutable single-owner state. {@code stateInit} is a 0-arg function whose body is the
     * record of the conductor's state fields at their initializers ({@code {count = 0, …}}); the
     * interpreter evaluates it once to seed the conductor's state cell, then its handlers read
     * ({@code this.field}) and mutate ({@code this.field = …}) that cell. Empty state → an empty
     * record. Present for every declared conductor; a cell is only seeded when a seated conductor's
     * handler actually fires.
     */
    public record CompiledConductor(String name, CompiledFunction stateInit) {}

    /**
     * A standing index declaration (docs/stream-queries.md §3, docs/keyed.md) — Slice B. A
     * NAMED key-mapping {@code T → K} over the element type {@code elementType}, decoupled from
     * {@code T}'s intrinsic identity (several views over one {@code T} may key it differently).
     * {@code kind} ∈ {@code unique|ordinal|cardinal} is a HINT for the physical structure the
     * optimizer may build (Slice C) — it is NOT enforced here. {@code keyFunction} is the
     * lowered {@code #index#} key-transform (a 1-param function {@code (n:T)} whose body is the
     * projection producing {@code K}).
     */
    public record CompiledIndex(String name, String kind, String elementType,
            CompiledFunction keyFunction) {}

    /**
     * The Conduit registered for the event type named {@code typeName} (its base name, as
     * the compile loop keyed them), or {@code null} if none. Exact bare-name lookup only —
     * the ancestor-aware variant is {@link #conduitsMatching(String)}.
     */
    public CompiledConduit conduitFor(String typeName) {
        return conduitsByType.get(typeName);
    }

    /**
     * Trait-aware conduit routing (docs/reactive-gui.md), the exact sibling of
     * {@link #actionsMatching(String)}: the conduits an emitted value of {@code typeName}
     * may fold — its own type's conduit, PLUS every conduit keyed by a trait the type
     * satisfies (transitively, via the {@link sibarum.pontif.core.symbolic.TraitRegistry}
     * base-chain). So a {@code conduit fold(e:CounterEvent, …)} folds an
     * {@code emit Increment(...)} when {@code Increment} is-a {@code CounterEvent}. The
     * caller (Step 2) rejects a multi-conduit match — a single event folded by several
     * conduits (the ordered pipeline) is a later step.
     */
    public List<CompiledConduit> conduitsMatching(String typeName) {
        int slash = typeName == null ? -1 : typeName.lastIndexOf('/');
        String bare = slash < 0 ? typeName : typeName.substring(slash + 1);
        List<CompiledConduit> out = new java.util.ArrayList<>();
        CompiledConduit exact = conduitFor(typeName);
        if (exact != null) out.add(exact);
        if (bare != null && !bare.equals(typeName)) {
            CompiledConduit bareHit = conduitFor(bare);
            if (bareHit != null && bareHit != exact) out.add(bareHit);
        }
        sibarum.pontif.core.symbolic.TraitRegistry tr = dispatch.traitRegistry();
        for (Map.Entry<String, CompiledConduit> e : conduitsByType.entrySet()) {
            String key = e.getKey();
            if (key.equals(typeName) || key.equals(bare)) continue;   // exact buckets already added
            if (tr.satisfiesBareTrait(key, typeName)) out.add(e.getValue());
        }
        return out;
    }

    /**
     * The static-graph single-owner rule (docs/orchestration.md, §"The conductor graph"): every event type
     * has at most ONE owning conduit. Two conduits whose keys stand in an ancestry relation — one is-a the
     * other's trait — both match an emit of the more-specific type, a routing conflict {@link #conduitsMatching}
     * would otherwise surface only when such an event is fired ({@code IrInterpreter.fireEvent}'s multi-conduit
     * guard). This proves the graph single-owner up front, before any event flows, and throws
     * {@link IllegalStateException} (the interpreter reports it as a load-time error) naming the two conduits.
     * The residual "diamond" case (a concrete type satisfying two unrelated conduit-key traits) stays the
     * runtime backstop. Idempotent and cheap — called once at {@code eval} start.
     */
    public void validateSingleOwnerConduits() {
        sibarum.pontif.core.symbolic.TraitRegistry traits = dispatch.traitRegistry();
        List<String> keys = new java.util.ArrayList<>(conduitsByType.keySet());
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                String k1 = keys.get(i);
                String k2 = keys.get(j);
                if (traits.satisfiesBareBoth(k1, k2) || traits.satisfiesBareBoth(k2, k1)) {
                    throw new IllegalStateException(
                            "conduits for event types '" + k1 + "' and '" + k2 + "' overlap — one is-a "
                                    + "the other, so an emit of the more-specific type matches both, but "
                                    + "every event type must have a single owning conduit (the conductor "
                                    + "graph). Merge them, or re-key one onto a disjoint type.");
                }
            }
        }
    }

    /**
     * A compiled Conduit (docs/reactive-gui.md): the stateful fold applied to every emitted
     * event of {@code eventTypeBareName} (and its subtypes) between {@code emit} and the
     * actions. {@code fold} is a 2-param function {@code (e:E, s:S) -> {R, S'}} — the event
     * and the current state to the dispatched value and the next state; {@code init} is a
     * 0-param function producing the seed state {@code S}. The interpreter threads the state
     * across emits in a per-conduit cell.
     */
    public record CompiledConduit(
            String eventTypeBareName, CompiledFunction fold, CompiledFunction init) {}

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
