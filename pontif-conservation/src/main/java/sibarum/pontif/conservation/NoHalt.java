package sibarum.pontif.conservation;

import sibarum.pontif.conservation.ConservationGraph.Ledger;
import sibarum.pontif.conservation.ConservationRoles.PathRoles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * No-Halt: the conservation ledger's divergence fact — <em>this function
 * provably never completes</em>. The complement of the summary fixpoint
 * (which assumes completion and checks what follows); together they are the
 * two halves of the partial-correctness ruling: "if it completes, it
 * conserves" and "it cannot complete."
 *
 * <p>The detector is a greatest fixpoint over the whole module:
 * <pre>
 *   D := all unambiguous functions;
 *   repeatedly remove any function having a branch-path that contains
 *   no call-to-D and no verbatim self re-entry;
 *   the stable D is the provably never-halting set.
 * </pre>
 * Sound by infinite descent under pure, strict evaluation: every completed
 * evaluation of a D-member would need a completed evaluation of a D-member
 * beneath it. Callers of never-halting functions inherit the fact for free —
 * no SCC machinery.
 *
 * <p>What it cannot claim (misses, never false positives): termination is
 * never proven — factorial's descent is arithmetic, the receipt graph's
 * territory; a divergent call in dead flow (an unused {@code let}) is
 * invisible from the result; overloaded names are excluded from D; calls
 * buried under residual flow are not collected. Silence means "no claim",
 * never "halts" — the name wears the boundary it lives on.
 */
public final class NoHalt {

    private NoHalt() {}

    /**
     * Function name → witness sentence, for every function in the ledger
     * provably never halting. Absence is no claim, not a halting verdict.
     */
    public static Map<String, String> of(Ledger ledger) {
        Map<String, Integer> declsPerName = new HashMap<>();
        for (ConservationGraph g : ledger.graphs()) {
            declsPerName.merge(g.functionName(), 1, Integer::sum);
        }
        Map<String, List<PathRoles>> pathsByName = new LinkedHashMap<>();
        for (ConservationGraph g : ledger.graphs()) {
            if (declsPerName.get(g.functionName()) == 1) {
                pathsByName.put(g.functionName(), ConservationRoles.of(g));
            }
        }

        Set<String> d = new LinkedHashSet<>(pathsByName.keySet());
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String name : List.copyOf(d)) {
                boolean everyPathDiverges = pathsByName.get(name).stream()
                        .allMatch(p -> pathDiverges(p, d));
                if (!everyPathDiverges) {
                    d.remove(name);
                    changed = true;
                }
            }
        }

        Map<String, String> witnesses = new LinkedHashMap<>();
        for (String name : d) {
            witnesses.put(name, witness(name, pathsByName.get(name), d));
        }
        return witnesses;
    }

    /** This path provably never halts, given the current never-halting set. */
    public static boolean pathDiverges(PathRoles path, Set<String> neverHalting) {
        return path.verbatimReentry
                || path.callees.stream().anyMatch(neverHalting::contains);
    }

    /** A per-path display detail, or null when the path makes no claim. */
    public static String pathWitness(PathRoles path, String enclosing,
            Set<String> neverHalting) {
        if (path.verbatimReentry) {
            return "re-enters '" + enclosing + "' with its own arguments";
        }
        for (String callee : path.callees) {
            if (neverHalting.contains(callee)) {
                return callee.equals(enclosing)
                        ? "re-enters '" + callee + "'"
                        : "calls '" + callee + "', which never halts";
            }
        }
        return null;
    }

    private static String witness(String name, List<PathRoles> paths, Set<String> d) {
        List<String> details = new ArrayList<>();
        for (PathRoles p : paths) {
            String detail = pathWitness(p, name, d);
            if (detail != null && !details.contains(detail)) details.add(detail);
        }
        return "no halting path: " + String.join("; ", details);
    }
}
