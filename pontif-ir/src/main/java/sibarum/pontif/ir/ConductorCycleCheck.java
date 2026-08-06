package sibarum.pontif.ir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The <b>cross-conductor cycle check</b> (docs/orchestration.md, §"Tracked gaps", gap 1) — the first real
 * consumer of {@link EmitInterface}'s statically-extracted <em>emits</em> interface (concurrent-runtime cut 3c).
 *
 * <p>It builds the directed <b>conductor emit graph</b> over the program's <b>seated</b> conductors: a node per
 * {@code spawn}-ed conductor, and an edge {@code A → B} when a handler of {@code A} may {@code emit} an event type
 * that a handler of {@code B} consumes. A cycle in that graph is a <b>feedback loop across the hive</b> — and cut
 * 3b's drive-to-quiescence rests on the message count draining to zero, so a genuine cycle is exactly the shape
 * that never quiesces (a THREAD-tier cycle spins the daemons forever; a main-lane cycle recurses until the stack
 * blows). This proves the graph acyclic <em>before</em> any event flows, naming the cycle — the same fail-closed
 * discipline {@link AlgebraicCheck} applies to algebraic recursion.
 *
 * <h2>Soundness stance — a lower bound, honest about it</h2>
 * The edges are the <b>statically-apparent</b> emits ({@link EmitInterface.EmittedTypes#known()}). An {@code emit}
 * whose event type isn't statically pinnable ({@code hasOpaque}) contributes no edge — so the graph is a
 * <b>lower bound</b> and the check may <em>miss</em> a cycle routed through an opaque emit, but it never invents
 * one: every edge it draws is a constructor/nominal {@code emit} that is really in the body. Matching is by bare
 * event-type name (exactly how the router keys its buckets); trait-routed subscription is not yet followed, so a
 * cycle that closes only through a trait supertype is likewise a miss, not a false positive. Missing a cycle
 * fails <em>open</em> (a possible hang the runtime still bounds); never rejecting a legitimate program is the
 * property worth keeping while the detector is a lower bound.
 */
public final class ConductorCycleCheck {

    private ConductorCycleCheck() {}

    /**
     * Rejects {@code module} with a {@link CompileException} if its seated conductors form an emit cycle.
     * A no-op for a program that seats nothing (no {@code spawn}) or whose seated conductors form a DAG.
     */
    public static void check(IrModule module) throws CompileException {
        Set<String> seated = new LinkedHashSet<>();
        for (IrStmt s : module.statements()) {
            if (s instanceof IrStmt.Spawn sp) seated.add(sp.conductorName());
        }
        if (seated.isEmpty()) return;   // nothing seated — no hive, no cycle to prove absent

        // Per conductor: the event types it consumes (its handlers' first-parameter sorts) and the types it
        // may emit (the union of its handler bodies' known emits). Only seated conductors are nodes — an
        // unseated conductor's handlers never run, so it can neither start nor continue a runtime cycle.
        Map<String, Set<String>> consumes = new LinkedHashMap<>();
        Map<String, Set<String>> emits = new LinkedHashMap<>();
        for (IrStmt s : module.statements()) {
            if (!(s instanceof IrStmt.FunctionDecl fd) || !fd.name().contains("#caction#")) continue;
            String conductor = conductorOfReactionKey(fd.name());
            if (!seated.contains(conductor)) continue;
            if (!fd.params().isEmpty()) {
                String consumed = bare(Coercions.baseName(fd.params().get(0).sort()));
                if (consumed != null) consumes.computeIfAbsent(conductor, k -> new LinkedHashSet<>()).add(consumed);
            }
            emits.computeIfAbsent(conductor, k -> new LinkedHashSet<>())
                    .addAll(EmitInterface.of(fd.body()).known());
        }

        // Edge A → B when A emits a type B consumes. Build an adjacency map over the seated nodes.
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        for (String a : seated) {
            Set<String> aEmits = emits.getOrDefault(a, Set.of());
            Set<String> targets = new LinkedHashSet<>();
            for (String b : seated) {
                for (String t : consumes.getOrDefault(b, Set.of())) {
                    if (aEmits.contains(t)) { targets.add(b); break; }
                }
            }
            edges.put(a, targets);
        }

        Optional<List<String>> cycle = firstCycle(seated, edges);
        if (cycle.isPresent()) {
            throw new CompileException(
                    "conductor emit cycle: " + String.join(" -> ", cycle.get())
                            + " — the seated conductors form a feedback loop, so the runtime cannot drive to "
                            + "quiescence (docs/orchestration.md, the conductor graph must be acyclic). Break the "
                            + "cycle, or route the feedback through a boundary that ends the forward flow.");
        }
    }

    /** Standard colored DFS: returns the first cycle found as a path {@code n → … → n} (repeating the entry). */
    private static Optional<List<String>> firstCycle(Set<String> nodes, Map<String, Set<String>> edges) {
        Set<String> done = new LinkedHashSet<>();
        Set<String> onStack = new LinkedHashSet<>();
        for (String n : nodes) {
            if (!done.contains(n)) {
                Optional<List<String>> c = dfs(n, edges, done, onStack, new ArrayList<>());
                if (c.isPresent()) return c;
            }
        }
        return Optional.empty();
    }

    private static Optional<List<String>> dfs(String node, Map<String, Set<String>> edges,
            Set<String> done, Set<String> onStack, List<String> path) {
        onStack.add(node);
        path.add(node);
        for (String next : edges.getOrDefault(node, Set.of())) {
            if (onStack.contains(next)) {
                int from = path.indexOf(next);
                List<String> cycle = new ArrayList<>(path.subList(from, path.size()));
                cycle.add(next);   // close the loop for a readable "A -> B -> A"
                return Optional.of(cycle);
            }
            if (!done.contains(next)) {
                Optional<List<String>> c = dfs(next, edges, done, onStack, path);
                if (c.isPresent()) return c;
            }
        }
        onStack.remove(node);
        path.remove(path.size() - 1);
        done.add(node);
        return Optional.empty();
    }

    /** The conductor name embedded in a {@code …#caction#<Conductor>#SEQ#handler} reaction key. */
    private static String conductorOfReactionKey(String key) {
        String marker = "#caction#";
        int start = key.indexOf(marker) + marker.length();
        int end = key.indexOf('#', start);
        return end < 0 ? key.substring(start) : key.substring(start, end);
    }

    /** The bare (module-path-stripped) name, matching how the router keys its buckets. */
    private static String bare(String name) {
        if (name == null) return null;
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }
}
