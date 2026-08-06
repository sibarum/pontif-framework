package sibarum.pontif.ir;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The <b>resolved routing table</b> for a run (docs/orchestration.md, §"The conductor graph" — "routing is a
 * resolved table, not a runtime lookup"). For each emitted event type it holds the type's <b>owning conduit(s)</b>
 * and its <b>subscriber actions</b> — the fan-out — resolved <em>once</em> and thereafter a map lookup, instead of
 * re-scanning every conduit/action bucket on every {@code emit}.
 *
 * <p>Each {@link Route} is a pure function of the (immutable, post-load) module, so memoizing is sound: the
 * trait-aware matches {@link CompiledModule#conduitsMatching} / {@link CompiledModule#actionsMatching} compute
 * never change for a given type once the module is linked. The per-<em>instance</em> refinement test (an action's
 * {@code matchSort} against the actual event) stays at the fire site — this table resolves only the candidate
 * <em>set</em>, which is what depends solely on the type.
 *
 * <p>Owned by the interpreter (one per run); population is lazy, converging to the full table as the program
 * emits each concrete type. It is the object emit-site specialization (local-fold vs cross-conductor enqueue) and
 * cross-conductor cycle detection will read.
 */
public final class RoutingTable {

    /** A type's resolved routing: its owning conduit bucket(s) and its subscriber actions, in fire order. */
    public record Route(List<CompiledModule.CompiledConduit> conduits,
                        List<CompiledModule.CompiledAction> subscribers) {}

    private final CompiledModule module;
    // Concurrent so a THREAD-tier conductor (concurrent-runtime cut 2) can resolve routes from its own
    // thread while the main lane does the same: each Route is a pure function of the (immutable, linked)
    // module, so a benign duplicate compute under race is harmless and the cache stays a plain map lookup.
    private final Map<String, Route> resolved = new ConcurrentHashMap<>();

    public RoutingTable(CompiledModule module) {
        this.module = module;
    }

    /** The module this table routes for — the interpreter rebuilds the table if it ever evaluates a new one. */
    public CompiledModule module() {
        return module;
    }

    /** The resolved route for an emitted {@code typeName}, computed once then cached. */
    public Route routeFor(String typeName) {
        return resolved.computeIfAbsent(typeName,
                t -> new Route(module.conduitsMatching(t), module.actionsMatching(t)));
    }
}
