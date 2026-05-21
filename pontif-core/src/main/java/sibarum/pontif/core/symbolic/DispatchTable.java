package sibarum.pontif.core.symbolic;

import sibarum.pontif.core.types.Sort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DispatchTable {

    private final Map<String, List<FunctionDecl>> declarations = new HashMap<>();

    public DispatchTable register(FunctionDecl decl) {
        declarations.computeIfAbsent(decl.name(), k -> new ArrayList<>()).add(decl);
        return this;
    }

    public List<FunctionDecl> declarationsFor(String name) {
        return List.copyOf(declarations.getOrDefault(name, List.of()));
    }

    public DispatchResult resolve(String name, List<SymExpr> arguments, Simplifier simplifier) {
        List<FunctionDecl> candidates = declarations.getOrDefault(name, List.of());
        if (candidates.isEmpty()) {
            return DispatchResult.noMatch("No declarations registered for '" + name + "'");
        }

        record MatchingCandidate(FunctionDecl decl, CompiledCall call) {}
        List<MatchingCandidate> matching = new ArrayList<>();
        for (FunctionDecl c : candidates) {
            if (c.parameters().size() != arguments.size()) continue;
            CompiledCall call = FunctionCheck.compileCall(c, arguments, simplifier);
            if (call.canExecute()) {
                matching.add(new MatchingCandidate(c, call));
            }
        }

        if (matching.isEmpty()) {
            return DispatchResult.noMatch(
                    "No matching declaration of '" + name + "' for the given arguments");
        }

        List<MatchingCandidate> mostSpecific = new ArrayList<>();
        for (MatchingCandidate c : matching) {
            boolean dominated = false;
            for (MatchingCandidate other : matching) {
                if (other == c) continue;
                if (isStrictlyMoreSpecific(other.decl(), c.decl(), simplifier)) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) {
                mostSpecific.add(c);
            }
        }

        if (mostSpecific.size() == 1) {
            return DispatchResult.resolved(mostSpecific.get(0).decl(), mostSpecific.get(0).call());
        }
        return DispatchResult.ambiguous(mostSpecific.stream().map(MatchingCandidate::decl).toList());
    }

    private static boolean isStrictlyMoreSpecific(FunctionDecl a, FunctionDecl b, Simplifier simp) {
        if (!isAtLeastAsSpecific(a, b, simp)) return false;
        return !isAtLeastAsSpecific(b, a, simp);
    }

    private static boolean isAtLeastAsSpecific(FunctionDecl a, FunctionDecl b, Simplifier simp) {
        if (a.parameters().size() != b.parameters().size()) return false;
        for (int i = 0; i < a.parameters().size(); i++) {
            Sort aSort = a.parameters().get(i).sort();
            Sort bSort = b.parameters().get(i).sort();
            if (!Refinements.imply(aSort, bSort, simp).isPassed()) {
                return false;
            }
        }
        return true;
    }
}
